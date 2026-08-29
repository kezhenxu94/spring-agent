package me.kezhenxu94.springagent.events.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.Getter;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything this module writes that the model did not, in the workspace's language: the brief a
 * triage run is given about a situation, and the elapsed times in it.
 *
 * <p>A message source of its own rather than the application's, for the reason {@code
 * FeishuMessages} gives — the bundle ships inside this module, and two modules claiming one
 * basename would be a fight over which one wins.
 *
 * <p>Only the short, assembled text lives here. The prompts are pages long and live as markdown
 * files instead, resolved by {@code TriagePrompts}: a paragraph of instructions folded into a
 * properties value is neither writable nor reviewable, which is the same split core makes between
 * {@code CoreMessages} and {@code LocalizedPrompt}.
 */
@Component
public class EventsMessages {

  /** Resolves to {@code events/messages.properties} and its per-locale siblings. */
  public static final String BASENAME = "events.messages";

  private static final long SECONDS_PER_MINUTE = 60;
  private static final long SECONDS_PER_HOUR = 3600;
  private static final long SECONDS_PER_DAY = 86400;

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public EventsMessages(final SpringAgentProperties properties) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(BASENAME);
    source.setDefaultEncoding("UTF-8");
    // English rather than the server's language when the configured locale ships no bundle: on a
    // server the host's language is an accident of how the image was built rather than a choice.
    source.setFallbackToSystemLocale(false);
    this.messageSource = source;
    this.locale = properties.locale() == null ? Locale.getDefault() : properties.locale();
  }

  /** The message for {@code key}, or the key itself where the bundle has no such entry. */
  public String get(final String key, final Object... arguments) {
    return messageSource.getMessage(key, arguments, key, locale);
  }

  /** What a source left empty, so a brief never has a blank where a value should be. */
  public String unknown(final String value) {
    return value == null || value.isBlank() ? get("brief-unknown") : value;
  }

  /**
   * How long ago {@code then} was, in words.
   *
   * <p>Words rather than a timestamp because this is the form the decision actually turns on:
   * whether these alerts are seconds or days apart is the whole question, and two ISO instants make
   * the model do arithmetic to find out — arithmetic it is not reliably good at, on the input that
   * matters most.
   *
   * <p>The numbers are handed over as strings on purpose. As numbers, {@code MessageFormat} would
   * group them by locale and render a long outage as {@code 1,000d ago}.
   */
  public String ago(final Instant then, final Instant now) {
    final var elapsed = Duration.between(then, now);
    if (elapsed.isNegative()) {
      // A clock that went backwards, or an event a source dated in the future. Neither is worth a
      // negative duration in a prompt.
      return get("elapsed-just-now");
    }
    final var seconds = elapsed.toSeconds();
    if (seconds < SECONDS_PER_MINUTE) {
      return get("elapsed-seconds", String.valueOf(seconds));
    }
    if (seconds < SECONDS_PER_HOUR) {
      return get("elapsed-minutes", String.valueOf(elapsed.toMinutes()));
    }
    if (seconds < SECONDS_PER_DAY) {
      return get(
          "elapsed-hours",
          String.valueOf(elapsed.toHours()),
          String.valueOf(elapsed.toMinutesPart()));
    }
    return get(
        "elapsed-days", String.valueOf(elapsed.toDays()), String.valueOf(elapsed.toHoursPart()));
  }
}
