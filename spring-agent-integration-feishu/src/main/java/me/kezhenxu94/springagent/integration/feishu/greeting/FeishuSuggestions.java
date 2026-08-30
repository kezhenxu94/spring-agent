package me.kezhenxu94.springagent.integration.feishu.greeting;

import static me.kezhenxu94.springagent.integration.feishu.handler.FeishuToasts.toast;

import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Starts a run from a suggestion tapped on the welcome card.
 *
 * <p>The card offers a few things worth asking for, and pressing one asks it — so a person who has
 * never used the agent gets an answer out of it without having had to work out what to type. The
 * run is an ordinary chat run in every other respect, which is what makes the reply thread under
 * the welcome card with no wiring of its own: {@code FeishuCardListener} is a bean listener that
 * covers every run and takes its reply target from the request.
 *
 * <p><b>The prompt is checked against what this deployment ships rather than taken as given.</b> A
 * card's callback value is client-supplied — it arrives over the wire from whoever pressed the
 * button, not from the card as it was rendered — so a crafted callback naming any prompt at all
 * would otherwise start a run with it under the presser's identity, and an identity here carries
 * their files, their credentials and their MCP servers. {@link FeishuUpdates#offers} is the whole
 * of the check: the prompt has to be one of the suggestions actually loaded.
 *
 * <p>Feishu drops a callback that takes more than three seconds and starting a run does not fit in
 * that — it creates and replies a card before it returns — so the run is handed to an executor and
 * the press is acknowledged with a toast, the same shape {@code FeishuQuestionAnswerHandler} uses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuSuggestions {

  /** The value a suggestion carries, distinguishing it from the card's other buttons. */
  public static final String ACTION = "suggest";

  private static final String VALUE_PROMPT = "prompt";

  private final SpringAgent springAgent;
  private final FeishuUpdates updates;
  private final FeishuMessages messages;
  private final ProcessedMessageRepo processedMessageRepo;

  /** See {@code FeishuQuestionAnswerHandler} for why this pool and not the scheduler's. */
  @Qualifier("applicationTaskExecutor")
  private final TaskExecutor taskExecutor;

  public P2CardActionTriggerResponse handle(final P2CardActionTrigger event) {
    final var action = event.getEvent().getAction();
    final var prompt = String.valueOf(action.getValue().get(VALUE_PROMPT));
    final var operator = event.getEvent().getOperator().getOpenId();
    if (!updates.offers(prompt)) {
      log.warn("Suggestion tap from {} naming a prompt this deployment does not offer", operator);
      return new P2CardActionTriggerResponse();
    }

    final var context = event.getEvent().getContext();
    final var cardMessageId = context.getOpenMessageId();
    // The card is the same card for as long as it is on screen, so a second press is a second run
    // saying the same thing. Keyed on the press rather than on the card, so pressing a different
    // suggestion still works and pressing the same one twice does not.
    final var requestId = "feishu-suggestion:" + cardMessageId + ":" + prompt;
    if (!processedMessageRepo.claim(requestId)) {
      log.info("Suggestion already taken up on card {}", cardMessageId);
      return new P2CardActionTriggerResponse();
    }

    taskExecutor.execute(
        () -> {
          try {
            springAgent.fire(
                AgentRequest.builder()
                    .requestId(requestId)
                    .scenario(BuiltInScenarios.CHAT)
                    .userId(operator)
                    .chatId(context.getOpenChatId())
                    .chatType("p2p")
                    // The welcome card is the top of the thread the answer hangs under, and the
                    // conversation the person carries on by replying to it.
                    .conversationId(cardMessageId)
                    .rootMessageId(cardMessageId)
                    .replyMessageId(cardMessageId)
                    .userMessage(user -> user.text(prompt))
                    .build());
          } catch (Exception e) {
            // Released, because nothing answered and nothing now will: holding the claim would make
            // the suggestion permanently dead on a card still showing it as pressable.
            processedMessageRepo.release(requestId);
            log.error("Failed to start a run from a suggestion on card {}", cardMessageId, e);
          }
        });
    return toast("success", messages.get("welcome-suggestion-taken"));
  }
}
