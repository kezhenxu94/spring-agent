package me.kezhenxu94.springagent.core.config;

import java.util.Locale;
import lombok.Getter;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything core writes into a conversation itself, in the workspace's language.
 *
 * <p>These read back to the model rather than to a person, so what a translation has to carry is
 * the instruction and not only the wording: a note the model misreads costs the user a question
 * they have already answered.
 *
 * <p>A message source of its own rather than the application's, for the reason {@code
 * FeishuMessages} gives: the bundle ships inside this module, and an application embedding it has
 * its own {@code messages.properties} that two modules cannot both claim.
 */
@Component
public class CoreMessages {

  /** Resolves to {@code core/messages.properties} and its per-locale siblings. */
  private static final String BASENAME = "core.messages";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public CoreMessages(final SpringAgentProperties properties) {
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
}
