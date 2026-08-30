package me.kezhenxu94.springagent.appweb.config;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Which language a request is answered in.
 *
 * <p>Two sources, in this order, which is what "detect it, and let me override it" means:
 *
 * <ol>
 *   <li>the {@code SPRING_AGENT_LOCALE} cookie, which the language switcher in the page sets. A
 *       choice, once made, has to survive the next visit;
 *   <li>failing that, the browser's own {@code Accept-Language}, so somebody who has never touched
 *       the switcher gets their language without asking for it.
 * </ol>
 *
 * <p>A cookie rather than a session attribute so the choice survives a restart of the server, and
 * so a browser that has not logged in yet — the login page is the first thing most people see —
 * already reads in their language.
 *
 * <p>The supported list is closed on purpose. {@code Accept-Language} is whatever the browser was
 * configured with, and honouring an arbitrary value would mean asking for a bundle that does not
 * exist and silently falling back per-key; matching against what is actually translated means an
 * unsupported language lands wholly in English rather than half in it.
 */
@Configuration
@RequiredArgsConstructor
public class WebLocaleConfiguration {

  /** Every language {@code web/messages*.properties} and the frontend's own bundle exist in. */
  public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE);

  public static final String LOCALE_COOKIE = "SPRING_AGENT_LOCALE";

  private final WebProperties properties;

  @Bean
  LocaleResolver localeResolver() {
    final var resolver = new CookieLocaleResolver(LOCALE_COOKIE);
    resolver.setDefaultLocale(properties.locale() == null ? Locale.ENGLISH : properties.locale());
    // Without this the resolver stops at the cookie and every first-time visitor gets the default,
    // which is exactly the automatic detection this class exists to provide.
    resolver.setDefaultLocaleFunction(
        request -> {
          final var acceptable = request.getLocales();
          while (acceptable.hasMoreElements()) {
            final var candidate = acceptable.nextElement();
            for (final var supported : SUPPORTED) {
              // By language only: zh-TW and zh-CN are both zh here, and answering a zh-TW reader in
              // simplified Chinese is closer than answering them in English.
              if (supported.getLanguage().equals(candidate.getLanguage())) {
                return supported;
              }
            }
          }
          return properties.locale() == null ? Locale.ENGLISH : properties.locale();
        });
    resolver.setCookieMaxAge(java.time.Duration.ofDays(365));
    resolver.setCookiePath("/");
    return resolver;
  }
}
