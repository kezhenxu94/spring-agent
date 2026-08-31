package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.stereotype.Component;

/** Ends a run somebody pressed stop on. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackStopHandler {

  private final SlackMessageListener listener;
  private final SpringAgent springAgent;
  private final SlackMessages messages;

  public Response handle(final BlockActionRequest request, final ActionContext ctx)
      throws java.io.IOException {
    final var payload = request.getPayload();
    final var action = payload.getActions().isEmpty() ? null : payload.getActions().get(0);
    final var runId = action == null ? null : action.getValue();
    if (Strings.isNullOrEmpty(runId)) {
      return ctx.ack();
    }
    final var operator = payload.getUser() == null ? null : payload.getUser().getId();
    final var run = listener.runFor(runId);
    log.info("Stop pressed: run={}, operator={}", runId, operator);
    if (run == null) {
      // Already over. Nothing to say about it that the message does not already show.
      return ctx.ack();
    }
    // Messages are shared, so in a channel everyone can see and press stop. Only the person the run
    // is answering gets to end it.
    if (!Objects.equals(operator, run.userId())) {
      log.info("Stop for run {} from {}, who is not {}", runId, operator, run.userId());
      ctx.respond(
          r ->
              r.responseType("ephemeral")
                  .replaceOriginal(false)
                  .text(messages.get("message-stop-not-yours")));
      return ctx.ack();
    }
    springAgent.cancel(runId);
    return ctx.ack();
  }
}
