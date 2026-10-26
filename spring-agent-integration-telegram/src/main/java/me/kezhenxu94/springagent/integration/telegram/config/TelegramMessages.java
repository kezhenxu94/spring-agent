package me.kezhenxu94.springagent.integration.telegram.config;

import java.util.Locale;
import lombok.Getter;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything a Telegram message says that the model did not write, in the chat's language: the
 * labels on the reply and the question form, and the notices a button press answers with.
 *
 * <p>A message source of its own rather than the application's, for the reason {@code
 * SlackMessages} gives: the bundle ships inside this module.
 */
@Component
public class TelegramMessages {

  /** Resolves to {@code telegram/messages.properties} and its per-locale siblings. */
  private static final String BASENAME = "telegram.messages";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public TelegramMessages(final TelegramProperties properties) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(BASENAME);
    source.setDefaultEncoding("UTF-8");
    // English rather than the server's language when the configured locale has no bundle.
    source.setFallbackToSystemLocale(false);
    this.messageSource = source;
    this.locale = properties.locale() == null ? Locale.getDefault() : properties.locale();
  }

  public String get(final String key, final Object... arguments) {
    return messageSource.getMessage(key, arguments, key, locale);
  }

  /** What is shown in place of the answer when a run fails. */
  public String error(final String message) {
    return get(
        "message-error",
        message == null || message.isEmpty() ? get("message-unknown-error") : message);
  }
}
