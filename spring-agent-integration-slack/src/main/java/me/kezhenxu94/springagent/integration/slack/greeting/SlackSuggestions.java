package me.kezhenxu94.springagent.integration.slack.greeting;

import com.google.common.base.Strings;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.slack.config.SlackAutoConfiguration;
import me.kezhenxu94.springagent.integration.slack.config.SlackIdentity;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Starts a run from a suggestion tapped on the welcome message.
 *
 * <p>The message offers a few things worth asking for, and pressing one asks it — so a person who
 * has never used the agent gets an answer out of it without having had to work out what to type.
 * The run is an ordinary chat run in every other respect, which is what makes the reply thread
 * under the welcome message with no wiring of its own: {@code SlackMessageListener} is a bean
 * listener that covers every run and takes its reply target from the request.
 *
 * <p><b>The prompt is checked against what this deployment ships rather than taken as given.</b> A
 * button's value is client-supplied — it arrives over the wire from whoever pressed it, not from
 * the message as it was rendered — so a crafted payload naming any prompt at all would otherwise
 * start a run with it under the presser's identity, and an identity here carries their files, their
 * credentials and their MCP servers. {@link SlackUpdates#offers} is the whole of the check.
 *
 * <p>Slack drops an interaction that is not acknowledged within three seconds, and starting a run
 * does not fit in that — it posts a message before it returns — so the run is handed to an executor
 * and the press is acknowledged at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackSuggestions {

  /** What a suggestion button is called, distinguishing it from the reply's other buttons. */
  public static final String ACTION_ID = "sa_suggest";

  private final SpringAgent springAgent;
  private final SlackUpdates updates;
  private final SlackMessages messages;
  private final SlackIdentity identity;
  private final ProcessedMessageRepo processedMessageRepo;

  @Qualifier(SlackAutoConfiguration.TASK_EXECUTOR)
  private final TaskExecutor taskExecutor;

  public Response handle(final BlockActionRequest request, final ActionContext ctx)
      throws IOException {
    final var payload = request.getPayload();
    final var action = payload.getActions().isEmpty() ? null : payload.getActions().get(0);
    final var prompt = action == null ? null : action.getValue();
    final var userId = payload.getUser() == null ? null : payload.getUser().getId();
    final var channelId = payload.getChannel() == null ? null : payload.getChannel().getId();
    final var messageTs = payload.getMessage() == null ? null : payload.getMessage().getTs();

    if (Strings.isNullOrEmpty(prompt) || Strings.isNullOrEmpty(userId)) {
      return ctx.ack();
    }
    if (!updates.offers(prompt)) {
      log.warn("Refusing a suggestion from {} that this deployment does not offer", userId);
      return ctx.ack();
    }
    // One run per press, however many times the button is pressed or the interaction redelivered.
    final var claim = "slack-suggestion:" + messageTs + ":" + prompt;
    if (!processedMessageRepo.claim(claim)) {
      return ctx.ack();
    }

    taskExecutor.execute(
        () -> {
          try {
            springAgent.fire(
                AgentRequest.builder()
                    .requestId(messageTs + ":suggest")
                    .scenario(BuiltInScenarios.CHAT)
                    .userId(userId)
                    .chatId(channelId)
                    .chatType("p2p")
                    .tenantId(identity.teamId())
                    .conversationId(messageTs)
                    .rootMessageId(messageTs)
                    .replyMessageId(messageTs)
                    .userMessage(user -> user.text(prompt))
                    .build());
          } catch (Exception e) {
            // Released, because nothing answered it and nothing now will.
            processedMessageRepo.release(claim);
            log.error("Could not start a run for a tapped suggestion", e);
          }
        });

    ctx.respond(
        r ->
            r.responseType("ephemeral")
                .replaceOriginal(false)
                .text(messages.get("welcome-suggestion-taken")));
    return ctx.ack();
  }
}
