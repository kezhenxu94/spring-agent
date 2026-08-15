package me.kezhenxu94.springagent.core.config;

import java.util.Locale;
import lombok.Getter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything core writes into a conversation itself, in the workspace's language.
 *
 * <p>These read back to the model rather than to a person, so what a translation has to carry is
 * the instruction and not only the wording: a note the model misreads costs the user a question
 * they have already answered.
 *
 * <p>The application's own {@link MessageSource}, rather than one of core's own, so that encoding,
 * caching and the system-locale fallback are configured once under {@code spring.messages} instead
 * of again here. What that asks of an application is that {@code spring.messages.basename} name
 * {@link #BASENAME} among its bundles — {@link #verifyBundleIsReachable()} is what says so out loud
 * when it does not, because the alternative is notes that read as bare message keys.
 */
@Component
public class CoreMessages {

  /** The bundle, as {@code spring.messages.basename} has to spell it. */
  public static final String BASENAME = "core/messages";

  /** Resolved at startup purely to prove the bundle is reachable. */
  private static final String SENTINEL_KEY = "questions-asked-heading";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public CoreMessages(final MessageSource messageSource, final SpringAgentProperties properties) {
    this.messageSource = messageSource;
    this.locale = properties.locale() == null ? Locale.getDefault() : properties.locale();
    verifyBundleIsReachable();
  }

  public String get(final String key, final Object... arguments) {
    return messageSource.getMessage(key, arguments, key, locale);
  }

  /**
   * Fails the context when the bundle is not among the application's, rather than letting every
   * note core writes come out as the key that produced it. A missing bundle is silent otherwise:
   * {@link #get} answers with the key, which is a valid string and reaches the model as one.
   */
  private void verifyBundleIsReachable() {
    if (SENTINEL_KEY.equals(get(SENTINEL_KEY))) {
      throw new IllegalStateException(
          "The '"
              + BASENAME
              + "' bundle is not reachable through the application's MessageSource, so the agent's"
              + " own notes would be written as bare message keys. Add '"
              + BASENAME
              + "' to spring.messages.basename.");
    }
  }
}
