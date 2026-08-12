package me.kezhenxu94.springagent.core.aot;

import java.util.List;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Reflection hints for the JSON logging stack that {@code logback.xml} wires up by class name.
 *
 * <p>Registered unconditionally through {@code META-INF/spring/aot.factories} rather than
 * {@code @ImportRuntimeHints} on a bean, because Logback is configured by Joran before the
 * application context exists — there is no bean whose registration could carry the hints.
 *
 * <p>Every type here is named as a string in the XML, so nothing in the bytecode refers to them and
 * the closed-world analysis cannot see them. They are only instantiated when {@code
 * ${LOG_APPENDER}} selects {@code STDOUT_JSON} — Logback skips an appender no {@code
 * <appender-ref>} points at — but that choice is made at runtime, so a single image has to cover
 * both. The failure is otherwise a Joran {@code DynamicClassLoadingException} on a deployment that
 * simply set an environment variable.
 */
public class LogbackJsonRuntimeHints implements RuntimeHintsRegistrar {

  private static final List<String> JORAN_COMPONENTS =
      List.of(
          "me.kezhenxu94.springagent.core.logging.JsonLogLayout",
          "ch.qos.logback.contrib.json.classic.JsonLayout",
          "ch.qos.logback.contrib.json.JsonLayoutBase",
          "ch.qos.logback.contrib.jackson.JacksonJsonFormatter",
          "ch.qos.logback.contrib.json.JsonFormatter",
          "ch.qos.logback.core.encoder.LayoutWrappingEncoder",
          "ch.qos.logback.core.ConsoleAppender");

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final String component : JORAN_COMPONENTS) {
      // Constructors because Joran instantiates by name, methods because it then drives the
      // <jsonFormatter>, <timestampFormat> and <appendLineSeparator> elements through the matching
      // reflective setters.
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader,
              component,
              MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_PUBLIC_METHODS,
              MemberCategory.INVOKE_DECLARED_METHODS);
    }
    hints.resources().registerPattern("logback.xml");
  }
}
