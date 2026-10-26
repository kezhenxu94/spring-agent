package me.kezhenxu94.springagent.integration.telegram.greeting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.integration.telegram.TelegramConversation;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.CallbackQuery;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramAutoConfiguration;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramIdentity;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.handler.TelegramKeyboards;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Starts a run from a suggestion tapped on the welcome message — {@code SlackSuggestions}'
 * counterpart. The button carries an index into this deployment's own list, so what is asked is
 * always something the deployment offers, whatever a client sends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSuggestions {

  private final SpringAgent springAgent;
  private final TelegramBotApi telegram;
  private final TelegramUpdates updates;
  private final TelegramMessages messages;
  private final TelegramIdentity identity;
  private final ProcessedMessageRepo processedMessageRepo;

  @Qualifier(TelegramAutoConfiguration.TASK_EXECUTOR)
  private final TaskExecutor taskExecutor;

  public void handle(final CallbackQuery query) {
    final var message = query.message();
    final var prompt = parse(query.data()).flatMap(updates::suggestion).orElse(null);
    if (prompt == null || message == null || query.from() == null) {
      answer(query, null);
      return;
    }
    final var chatId = String.valueOf(message.chat().id());
    final var userId = String.valueOf(query.from().id());
    final var requestId = new TelegramMessageId(chatId, message.messageId()).key() + ":suggest";
    // One run per press, however many times the button is pressed or the update redelivered.
    final var claim = "telegram-suggestion:" + requestId + ":" + query.data();
    if (!processedMessageRepo.claim(claim)) {
      answer(query, null);
      return;
    }
    // The welcome is only sent into a private chat, which is one conversation.
    final var conversation = new TelegramConversation(chatId, null, null);
    taskExecutor.execute(
        () -> {
          try {
            springAgent.fire(
                AgentRequest.builder()
                    .requestId(requestId)
                    .scenario(BuiltInScenarios.CHAT)
                    .userId(userId)
                    .chatId(chatId)
                    .chatType("p2p")
                    .tenantId(identity.tenantId())
                    .conversationId(conversation.key())
                    .rootMessageId(conversation.key())
                    .replyMessageId(String.valueOf(message.messageId()))
                    .userMessage(user -> user.text(prompt))
                    .build());
          } catch (Exception e) {
            processedMessageRepo.release(claim);
            log.error("Could not start a run for a tapped suggestion", e);
          }
        });
    answer(query, messages.get("welcome-suggestion-taken"));
  }

  private static java.util.Optional<Integer> parse(final String data) {
    if (data == null || !data.startsWith(TelegramKeyboards.SUGGESTION)) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(
          Integer.parseInt(data.substring(TelegramKeyboards.SUGGESTION.length())));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }
  }

  private void answer(final CallbackQuery query, final String text) {
    try {
      telegram.answerCallbackQuery(query.id(), text);
    } catch (Exception e) {
      log.debug("Could not answer a suggestion press: {}", e.getMessage());
    }
  }
}
