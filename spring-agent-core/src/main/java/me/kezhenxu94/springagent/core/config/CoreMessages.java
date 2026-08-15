package me.kezhenxu94.springagent.core.config;

import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything core writes into a conversation itself, in the workspace's language.
 *
 * <p>These read back to the model rather than to a person, so what a translation has to carry is
 * the instruction and not only the wording: a note the model misreads costs the user a question
 * they have already answered.
 *
 * <p>Read through the application's own {@link MessageSource}, so that encoding, caching and the
 * system-locale fallback are configured once under {@code spring.messages} rather than again here.
 * An application has to name {@link #BASENAME} among its bundles for that to resolve.
 */
@Slf4j
@Component
public class CoreMessages {

  /** The bundle, as {@code spring.messages.basename} has to spell it. */
  public static final String BASENAME = "core/messages";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public CoreMessages(final MessageSource messageSource, final SpringAgentProperties properties) {
    this.messageSource = messageSource;
    this.locale = properties.locale() == null ? Locale.getDefault() : properties.locale();
  }

  /**
   * The message, or the key itself — which the model would read as the note — when it is missing.
   */
  public String get(final String key, final Object... arguments) {
    final var message = messageSource.getMessage(key, arguments, key, locale);
    if (key.equals(message)) {
      log.warn(
          "No '{}' message in {}; is '{}' in spring.messages.basename?", key, locale, BASENAME);
    }
    return message;
  }
}
