package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.observing.EventIntakes;
import me.kezhenxu94.springagent.core.observing.Observation;
import me.kezhenxu94.springagent.core.observing.Route;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramTypes.Message;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramIdentity;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import org.springframework.stereotype.Component;

/**
 * Reports a group message the bot was not addressed in, so that the agent can watch a conversation
 * it is not part of — {@code SlackChatObservations}' counterpart, with the same promises: never
 * throws, off until a chat is named, the text only.
 *
 * <p>Telegram adds a precondition the configuration cannot satisfy on its own: with privacy mode
 * on, which is a new bot's default, a group sends a bot only what is addressed to it, so nothing
 * would ever arrive here. Turning it off is a BotFather setting — see the application's README.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramChatObservations {

  /**
   * The source name these are configured under, and the same literal as {@code
   * EventsProperties.TELEGRAM_CHAT} — textually coupled for the reason {@code
   * SlackChatObservations.SOURCE} is. Change one and change the other.
   */
  public static final String SOURCE = "telegram-chat";

  private final TelegramProperties properties;
  private final TelegramIdentity identity;
  private final TelegramMessages messages;
  private final EventIntakes eventIntakes;

  public void observed(final Message message, final long updateId) {
    try {
      if (eventIntakes.isEmpty()) {
        return;
      }
      final var chatId = String.valueOf(message.chat().id());
      if (!properties.observedChatIds().contains(chatId)) {
        return;
      }
      final var text = message.textOrCaption();
      if (Strings.isNullOrEmpty(text)) {
        return;
      }
      final var title =
          Strings.isNullOrEmpty(message.chat().title()) ? chatId : message.chat().title();
      eventIntakes.observe(
          Observation.builder()
              .source(SOURCE)
              // Telegram's own update id, stable across a redelivery and new for the next one.
              .deliveryId(String.valueOf(updateId))
              .kind("chat.message")
              // One rolling window per group, as on Slack: a chat is a grouping that is right by
              // construction.
              .correlationKey(SOURCE + ":" + chatId)
              .title(messages.get("chat-observation-title", title))
              .summary(
                  messages.get(
                      "chat-observation-said",
                      message.from().displayName(),
                      text,
                      String.valueOf(message.messageId())))
              .payloadJson(null)
              .route(
                  Route.builder()
                      .chatId(chatId)
                      .chatType("group")
                      .groupId(chatId)
                      .tenantId(identity.tenantId())
                      .build())
              .build());
    } catch (Exception e) {
      log.warn("Failed to observe message {} in {}", message.messageId(), message.chat().id(), e);
    }
  }
}
