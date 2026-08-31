package me.kezhenxu94.springagent.integration.slack.config;

import java.util.Locale;
import lombok.Getter;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything a Slack message says that the model did not write, in the workspace's language: the
 * labels on the reply message and the question form, and the ephemeral notes a button answers with.
 *
 * <p>A message source of its own rather than the application's, because the bundle ships inside
 * this module and an application embedding it has its own {@code messages.properties} to think
 * about; two modules claiming one basename would be a fight over which one wins. The same
 * arrangement {@code FeishuMessages} has, for the same reason.
 */
@Component
public class SlackMessages {

  /** Resolves to {@code slack/messages.properties} and its per-locale siblings. */
  private static final String BASENAME = "slack.messages";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public SlackMessages(final SlackProperties properties) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(BASENAME);
    source.setDefaultEncoding("UTF-8");
    // English rather than the server's language when the configured locale has no bundle: the
    // fallback is otherwise whatever the host is set to, which is rarely what was asked for.
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
