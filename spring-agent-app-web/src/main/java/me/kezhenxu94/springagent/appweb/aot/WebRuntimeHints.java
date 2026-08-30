package me.kezhenxu94.springagent.appweb.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What the browser is served, and what says it in the reader's language.
 *
 * <p>Native image is the reason this class exists at all: resources are not on the image's
 * classpath unless something asked for them, and nothing infers a static site. The failure is a
 * nasty one to diagnose because the JVM build passes — the binary starts, answers, and serves an
 * empty page.
 */
public class WebRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    hints
        .resources()
        // The page and everything it loads, at every depth: vendor/ is a directory of its own.
        .registerPattern("static/*")
        .registerPattern("static/**")
        // The server's own text. Which locale is wanted is only known when a request arrives, so
        // every bundle has to be in the image rather than the one the build happened to want.
        .registerPattern("web/messages*.properties");
  }
}
