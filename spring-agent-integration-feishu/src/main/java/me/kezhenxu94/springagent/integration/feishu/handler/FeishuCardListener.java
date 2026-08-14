package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives every Feishu-originated agent run a card to stream into, whichever way the run was started:
 * it creates a card, replies it onto the message the run answers, and attaches a {@link
 * FeishuCardUpdater} to the run. A chat message and a scheduled task firing differ only in which
 * message the card is replied onto, so both go through here rather than each building its own.
 *
 * <p>A bean rather than something the message handler or the scheduler calls, so neither has to
 * know that cards exist and core does not have to know that Feishu does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuCardListener implements AgentResponseListener {

  final Client feishu;
  final JsonMapper om;
  final SpringAgentProperties appConfiguration;
  final RestTemplate restTemplate;
  final FeishuProperties feishuProperties;
  final UserWorkspaceFactory userWorkspaceFactory;
  final PendingQuestionRepo pendingQuestionRepo;
  final FeishuQuestionForm questionForm;

  // Not final, matching FeishuTools#feishuReplyCard: @Value on a field is an injection point in its
  // own right, and AOT generates a plain field assignment for it, which cannot target a final field
  // the way the JVM's reflective injection can.
  @Value("${app.feishu.reply-card:classpath:/feishu/reply-card.json}")
  Resource feishuReplyCard;

  /**
   * Which run each card's own message belongs to. The card's stop button knows only the message the
   * card was sent as, and cancelling is in-memory anyway, so this is exactly as durable as the
   * thing it feeds.
   */
  private final ConcurrentMap<String, String> runIdsByCardMessage = new ConcurrentHashMap<>();

  /** The run the card sent as {@code cardMessageId} belongs to, or {@code null} if it has ended. */
  public String runIdFor(final String cardMessageId) {
    return runIdsByCardMessage.get(cardMessageId);
  }

  @Override
  public void onStart(final AgentRunRegistry registry) {
    final var request = registry.request();
    final var replyTo =
        Strings.isNullOrEmpty(request.replyMessageId())
            ? request.rootMessageId()
            : request.replyMessageId();
    // Nothing to reply onto means the run did not come from Feishu at all.
    if (Strings.isNullOrEmpty(replyTo)) {
      return;
    }
    final var runId = request.requestId();
    try {
      final var cardId = createCard(runId);
      final var cardMessageId = cardId == null ? null : replyCard(runId, replyTo, cardId);
      if (cardMessageId == null) {
        abortOrCarryOn(registry, "failed to create a Feishu reply card");
        return;
      }
      log.info("Card {} sent as message {} for run {}", cardId, cardMessageId, runId);

      final var cardUpdater =
          new FeishuCardUpdater(
              feishu,
              om,
              cardId,
              request.userId(),
              restTemplate,
              userWorkspaceFactory,
              appConfiguration.ai().modelPricing(),
              feishuProperties.cardText());
      registry.addResponseListener(cardUpdater);
      registry.addTodoEventHandler(cardUpdater);
      registry.addToolContext(FeishuCardUpdater.TOOL_CONTEXT_KEY.key(), cardUpdater);

      // Only a chat run, and registering this is what decides whether the agent may ask at all. A
      // scheduled task has no conversation memory, so an answer arriving later would have nothing
      // to rejoin — its prompt already tells the model there is nobody to ask.
      if (request.scenario() == AgentScenario.CHAT) {
        registry.addQuestionHandler(
            new FeishuQuestionHandler(
                request,
                cardUpdater,
                cardId,
                pendingQuestionRepo,
                questionForm,
                om,
                appConfiguration.ai().tools().askUserQuestion().ttl()));
      }

      runIdsByCardMessage.put(cardMessageId, runId);
      registry.addResponseListener(new CardRun(runId, cardMessageId));
    } catch (Exception e) {
      log.error("Failed to attach a Feishu card to run {}", runId, e);
      abortOrCarryOn(registry, "failed to attach a Feishu reply card: " + e.getMessage());
    }
  }

  /**
   * A chat message whose card never appeared has nowhere to put its answer, so the run is
   * pointless; a scheduled task does its work regardless and goes ahead unreported.
   */
  private static void abortOrCarryOn(final AgentRunRegistry registry, final String reason) {
    if (registry.request().scenario() == AgentScenario.CHAT) {
      registry.abort(reason);
    }
  }

  /** The per-run half: the shared listener is a singleton, this holds one run's message ids. */
  @RequiredArgsConstructor
  private final class CardRun implements AgentResponseListener {
    private final String runId;
    private final String cardMessageId;

    @Override
    public void onFinished(final AgentOutcome outcome) {
      log.info("Run {} finished: outcome={}", runId, outcome);
      // The map is what the stop button reads to find a run by the card it was pressed on, so a
      // finished run has to leave it or the entry outlives the run it names.
      runIdsByCardMessage.remove(cardMessageId);
    }
  }

  private String createCard(final String runId) throws Exception {
    final var cardJson =
        feishuProperties
            .cardText()
            .render(feishuReplyCard.getContentAsString(StandardCharsets.UTF_8));
    final var response =
        feishu
            .cardkit()
            .v1()
            .card()
            .create(
                CreateCardReq.newBuilder()
                    .createCardReqBody(
                        CreateCardReqBody.newBuilder().type("card_json").data(cardJson).build())
                    .build());
    if (response.getCode() != 0) {
      log.error("Failed to create card for run {}: {}", runId, om.writeValueAsString(response));
      return null;
    }
    return response.getData().getCardId();
  }

  /** Returns the id of the message the card was sent as, or {@code null} if it was not sent. */
  private String replyCard(final String runId, final String replyTo, final String cardId)
      throws Exception {
    final var response =
        feishu
            .im()
            .v1()
            .message()
            .reply(
                ReplyMessageReq.newBuilder()
                    .messageId(replyTo)
                    .replyMessageReqBody(
                        ReplyMessageReqBody.newBuilder()
                            .msgType("interactive")
                            .content(
                                String.format(
                                    """
                                    {
                                      "type": "card",
                                      "data": {
                                        "card_id": "%s"
                                      }
                                    }
                                    """,
                                    cardId))
                            .build())
                    .build());
    if (response.getCode() != 0) {
      log.error("Failed to send card for run {}: {}", runId, om.writeValueAsString(response));
      return null;
    }
    return response.getData().getMessageId();
  }
}
