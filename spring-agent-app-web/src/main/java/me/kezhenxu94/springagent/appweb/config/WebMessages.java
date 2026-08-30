package me.kezhenxu94.springagent.appweb.config;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
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
 */
@Component
@RequiredArgsConstructor
public class WebMessages {

  private static final String BASENAME = "web.messages";

  private final ResourceBundleMessageSource messageSource = messageSource();

  private final WebProperties properties;

  private static ResourceBundleMessageSource messageSource() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(BASENAME);
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
    return messageSource.getMessage(key, arguments, key, locale());
  }
}
