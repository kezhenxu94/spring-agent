package me.kezhenxu94.springagent.integration.feishu.config;

import java.util.Locale;
import lombok.Getter;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Everything a Feishu card says that the model did not write, in the workspace's language: the
 * labels on the reply card and the question form, and the toasts a card action answers with.
 *
 * <p>A message source of its own rather than the application's, because the bundle ships inside
 * this module and an application embedding it has its own {@code messages.properties} to think
 * about; two modules claiming one basename would be a fight over which one wins.
 */
@Component
public class FeishuMessages {

  /** Resolves to {@code feishu/messages.properties} and its per-locale siblings. */
  private static final String BASENAME = "feishu.messages";

  private final MessageSource messageSource;

  @Getter private final Locale locale;

  public FeishuMessages(final FeishuProperties properties) {
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
        "card-error", message == null || message.isEmpty() ? get("card-unknown-error") : message);
  }

  /**
   * Fills the reply card template's label placeholders. A template that spells its labels out
   * instead is left as it is, so a deployment can still ship a card of its own and ignore these.
   */
  public String renderCard(final String cardJson) {
    return cardJson
        .replace("{stop}", jsonEscaped(get("card-stop")))
        .replace("{reasoning}", jsonEscaped(get("card-reasoning")))
        .replace("{references}", jsonEscaped(get("card-references")))
        .replace("{conversationHint}", jsonEscaped(get("card-conversation-hint")));
  }

  /** Fills the question form template's label placeholders. */
  public String renderQuestionForm(final String formJson) {
    return formJson
        .replace("{selectHint}", jsonEscaped(get("question-select-hint")))
        .replace("{otherHint}", jsonEscaped(get("question-other-hint")))
        .replace("{submitText}", jsonEscaped(get("question-submit")));
  }

  /**
   * Substitution happens in a template's text rather than its parsed form, so a label carrying a
   * quote or a newline would otherwise produce a card that no longer parses.
   */
  static String jsonEscaped(final String value) {
    final var escaped = new StringBuilder(value.length());
    for (final var c : value.toCharArray()) {
      switch (c) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20) {
            escaped.append(String.format("\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }
    return escaped.toString();
  }
}
