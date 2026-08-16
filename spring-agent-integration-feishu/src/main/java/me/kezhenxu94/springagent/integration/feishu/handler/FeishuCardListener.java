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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a Feishu-originated agent run a card to stream into, whichever way the run was started: it
 * creates a card, replies it onto the message the run answers, and attaches a {@link
 * FeishuCardUpdater} to the run. A chat message and a scheduled task firing differ only in which
 * message the card is replied onto, so both go through here rather than each building its own.
 *
 * <p>A bean rather than something the message handler or the scheduler calls, so neither has to
 * know that cards exist and core does not have to know that Feishu does.
 *
 * <p>A background run is the exception and gets no card at all: it is unattended by definition, so
 * there is nobody the card would be streaming to. Such a run says whatever it has to say by sending
 * a message itself, and a card announcing the run is a second message on top of that — or, for a
 * task that decided it had nothing to say, the only one, which is exactly the message its author
 * did not want. The one thing still reported here is a failure, since a run that fell over is the
 * one thing it cannot report for itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuCardListener implements AgentResponseListener {

  final Client feishu;
  final JsonMapper om;
  final SpringAgentProperties appConfiguration;
  final RestTemplate restTemplate;
  final FeishuMessages messages;
  final UserWorkspaceFactory userWorkspaceFactory;
  final PendingQuestionRepo pendingQuestionRepo;
  final FeishuQuestionForm questionForm;
  final FeishuMessageCard messageCard;

  // Not final, matching FeishuTools#feishuReplyCard: @Value on a field is an injection point in its
  // own right, and AOT generates a plain field assignment for it, which cannot target a final field
  // the way the JVM's reflective injection can.
  @Value("${app.feishu.reply-card:classpath:/feishu/reply-card.json}")
  Resource feishuReplyCard;

  /**
   * A run reachable from the card it is streaming into, and the user it was started for: the stop
   * button is on a card, and a card in a group chat is in front of everyone.
   */
  public record StoppableRun(String runId, String userId) {}

  /**
   * Which run each card's own message belongs to. The card's stop button knows only the message the
   * card was sent as, and cancelling is in-memory anyway, so this is exactly as durable as the
   * thing it feeds.
   */
  private final ConcurrentMap<String, StoppableRun> runsByCardMessage = new ConcurrentHashMap<>();

  /** The run the card sent as {@code cardMessageId} belongs to, or {@code null} if it has ended. */
  public StoppableRun runFor(final String cardMessageId) {
    return runsByCardMessage.get(cardMessageId);
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
    // No card, and so no stop button either: a background run is not on screen to be stopped, and
    // the scheduled task behind it is cancelled by name with CancelScheduledTask.
    if (request.background()) {
      registry.addResponseListener(new BackgroundRun(runId, replyTo));
      return;
    }
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
              messages);
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

      runsByCardMessage.put(cardMessageId, new StoppableRun(runId, request.userId()));
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
      runsByCardMessage.remove(cardMessageId);
    }
  }

  /**
   * The whole of a background run's reporting: silence, unless the run failed. The error is kept
   * from {@code onError} because {@link AgentOutcome} says only that the run failed, and a notice
   * that cannot say what went wrong is barely worth sending.
   */
  @RequiredArgsConstructor
  private final class BackgroundRun implements AgentResponseListener {
    private final String runId;
    private final String replyTo;
    private volatile Throwable error;

    @Override
    public void onError(final Throwable error) {
      this.error = error;
    }

    @Override
    public void onFinished(final AgentOutcome outcome) {
      log.info("Background run {} finished: outcome={}", runId, outcome);
      if (outcome != AgentOutcome.FAILED) {
        return;
      }
      final var reason =
          error == null || Strings.isNullOrEmpty(error.getMessage())
              ? messages.get("card-unknown-error")
              : error.getMessage();
      replyMessage(runId, replyTo, messages.get("background-run-failed", blockQuoted(reason)));
    }
  }

  /**
   * The reason as a Markdown quote, so a failure that runs to several lines reads as one quoted
   * passage rather than as prose the agent wrote.
   */
  private static String blockQuoted(final String reason) {
    return reason.lines().map(line -> "> " + line).collect(Collectors.joining("\n"));
  }

  /**
   * Replies {@code markdown} onto {@code replyTo} as a finished card. The same card the agent's
   * answers arrive in, because this is one of them in every way that matters to the reader: it is
   * the run talking about itself, just written by the runtime rather than the model.
   */
  private void replyMessage(final String runId, final String replyTo, final String markdown) {
    try {
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
                              .content(messageCard.render(markdown))
                              .build())
                      .build());
      if (response.getCode() != 0) {
        log.error("Failed to reply to {} for run {}: {}", replyTo, runId, response.getMsg());
      }
    } catch (Exception e) {
      log.error("Failed to reply to {} for run {}", replyTo, runId, e);
    }
  }

  private String createCard(final String runId) throws Exception {
    final var cardJson =
        messages.renderCard(feishuReplyCard.getContentAsString(StandardCharsets.UTF_8));
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
