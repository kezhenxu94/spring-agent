package me.kezhenxu94.springagent.core.config;

import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * A module's own text, in the workspace's language — the counterpart of {@link
 * me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts} for what a tool <em>answers</em>
 * rather than for how it describes itself.
 *
 * <p>A tool result is read by the model and turned straight into the sentence the user reads, so
 * English here is an English answer to a question asked in another language, and — before that —
 * English in the reasoning the user is watching. That makes a tool result as much a translation
 * target as the prompt is.
 *
 * <p>Its own {@link ResourceBundleMessageSource} rather than the application's, for the reason
 * {@code MessagesDefaults} gives: the bundle ships inside the module, an application embedding it
 * has its own {@code messages.properties} to think about, and two modules claiming one basename
 * would be a fight over which one wins. {@link CoreMessages} is the exception, since core is the
 * one thing every application here already has.
 *
 * <p>{@code setFallbackToSystemLocale(false)} is load-bearing: without it a locale that ships no
 * bundle falls back to whatever the host is configured for, so an English deployment on a Chinese
 * server would answer in Chinese and a Chinese one on an English server in English — neither being
 * what was asked for.
 */
@Slf4j
@Getter
public class ModuleMessages {

  private final MessageSource messageSource;

  private final Locale locale;

  /**
   * @param basename the bundle as a resource bundle spells it, e.g. {@code shell-docker.messages}
   * @param locale which language to serve, the host's when {@code null}
   */
  public ModuleMessages(final String basename, final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(basename);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    this.messageSource = source;
    this.locale = locale == null ? Locale.getDefault() : locale;
  }

  /**
   * The message, or the key itself — which the model would then read as the answer — when it is
   * missing. Logged, because a tool answering with {@code bash-unknown-id} looks like a bug in the
   * tool rather than a line left out of a bundle.
   */
  public String get(final String key, final Object... arguments) {
    final var message = messageSource.getMessage(key, arguments, key, locale);
    if (key.equals(message)) {
      log.warn("No '{}' message in {}; the model was handed the key instead", key, locale);
    }
    return message;
  }
}
