package me.kezhenxu94.springagent.integration.telegram.handler;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.CallbackQuery;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import org.springframework.stereotype.Component;

/** Ends a run somebody pressed stop on — only the person it is answering, as on Slack. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramStopHandler {

  private final TelegramMessageListener listener;
  private final SpringAgent springAgent;
  private final TelegramBotApi telegram;
  private final TelegramMessages messages;

  public void handle(final CallbackQuery query) {
    final var runId = query.data().substring(TelegramKeyboards.STOP.length());
    final var operator = query.from() == null ? null : String.valueOf(query.from().id());
    final var run = listener.runFor(runId);
    log.info("Stop pressed: run={}, operator={}", runId, operator);
    String notice = null;
    if (run != null) {
      if (Objects.equals(operator, run.userId())) {
        springAgent.cancel(runId);
      } else {
        notice = messages.get("message-stop-not-yours");
      }
    }
    try {
      telegram.answerCallbackQuery(query.id(), notice);
    } catch (Exception e) {
      log.debug("Could not answer a stop press: {}", e.getMessage());
    }
  }
}
