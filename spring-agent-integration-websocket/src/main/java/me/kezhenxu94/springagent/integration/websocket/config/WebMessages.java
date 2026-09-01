package me.kezhenxu94.springagent.integration.websocket.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Text this application writes itself, as opposed to what the model produced.
 *
 * <p>Its own {@code MessageSource} over {@code web/messages*.properties}, the pattern {@code
 * FeishuMessages} and {@code CliMessages} follow, so a module's text travels with the module.
 *
 * <p>Where it parts company with those two is which language it answers in. A command line has one
 * user and a bot has one tenant, so both read a configured locale once and keep it. A web server
 * has as many readers as there are open tabs, and the language belongs to the request rather than
 * to the process — so {@link #get} reads {@link LocaleContextHolder}, which Spring's {@code
 * LocaleResolver} fills in per request from the reader's own cookie or {@code Accept-Language}. See
 * {@link WebLocaleConfiguration}.
 *
 * <p><b>A consumer embedding this module can put bundles of their own in front.</b> Every basename
 * in {@code app.web.messages} is consulted before this module's, key by key, so overriding one
 * string means writing that one key rather than copying the whole bundle and then maintaining a
 * copy that silently loses whatever is added here later. That is the extension point for a
 * deployment that renames the agent per language — {@code app-title} — or rewords a refusal in its
 * own voice, and it costs them no code.
 */
@Component
public class WebMessages {

  /** This module's own bundle, always last, so a key nobody overrode still resolves. */
  private static final String BASENAME = "web.messages";

  /** The agent's name, the one key here that a page renders rather than a person reads. */
  public static final String TITLE = "app-title";

  private final ResourceBundleMessageSource messageSource;

  private final WebProperties properties;

  public WebMessages(final WebProperties properties) {
    this.properties = properties;
    this.messageSource = messageSource(properties.messages());
  }

  private static ResourceBundleMessageSource messageSource(final List<String> extra) {
    final var source = new ResourceBundleMessageSource();
    // In order: a consumer's bundles first, this module's last. setBasenames resolves a key by
    // walking the list and stopping at the first bundle that has it, so an override is per key
    // rather than per file — a bundle naming one key leaves every other one alone.
    final var basenames = new ArrayList<>(extra);
    basenames.add(BASENAME);
    source.setBasenames(basenames.toArray(String[]::new));
    source.setDefaultEncoding("UTF-8");
    // English rather than whatever the host happens to be set to: a server's own locale says
    // nothing about who is reading, and falling back to it would answer a Chinese browser in
    // whichever language the container image was built with.
    source.setFallbackToSystemLocale(false);
    return source;
  }

  /** The language this request is being answered in. */
  public Locale locale() {
    final var resolved = LocaleContextHolder.getLocale();
    if (resolved != null) {
      return resolved;
    }
    return properties.locale() == null ? Locale.ENGLISH : properties.locale();
  }

  /** The key is its own default, so a missing translation degrades rather than throws. */
  public String get(final String key, final Object... arguments) {
    return get(locale(), key, arguments);
  }

  /**
   * The same, in a language named rather than the reader's.
   *
   * <p>For the one thing a response says in every language at once: the page's name, which the
   * switcher changes without asking the server again.
   */
  public String get(final Locale language, final String key, final Object... arguments) {
    return messageSource.getMessage(key, arguments, key, language);
  }
}
