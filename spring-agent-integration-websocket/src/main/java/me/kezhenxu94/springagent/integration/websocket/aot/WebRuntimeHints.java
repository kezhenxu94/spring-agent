package me.kezhenxu94.springagent.integration.websocket.aot;

import me.kezhenxu94.springagent.integration.websocket.run.RunEvent;
import org.springframework.aot.hint.MemberCategory;
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

    // Every frame a run streams over. Serialized by hand rather than by a message converter — see
    // RunStreamSubscriptions — so nothing in the AOT processing of a controller signature infers
    // it, and a binary without it streams a run as a series of Jackson failures.
    hints
        .reflection()
        .registerType(RunEvent.class, MemberCategory.ACCESS_DECLARED_FIELDS)
        .registerType(RunEvent.class, MemberCategory.INVOKE_DECLARED_METHODS);
  }
}
