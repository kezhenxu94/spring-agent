package me.kezhenxu94.springagent.appcli;

import me.kezhenxu94.springagent.appcli.aot.CliRuntimeHints;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * The agent as a command-line tool. Unlike {@code spring-agent-app} there is no web server, no
 * actuator and no security: the only thing this process talks to is the terminal it was started
 * from, and its whole state lives under {@code ~/.spring-agent}.
 *
 * <p>Standard output belongs to the user, so nothing may log to it — see {@code logback.xml}, whose
 * appender defaults to {@code FILE} here rather than {@code CONSOLE}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@ImportRuntimeHints(CliRuntimeHints.class)
public class SpringAgentCliApplication {

  public static void main(final String[] args) {
    try {
      // NONE rather than left to inference: spring-agent-core pulls in a web stack through Spring
      // AI's RestClient, and Boot would start a servlet container on the strength of that alone.
      new SpringApplicationBuilder(SpringAgentCliApplication.class)
          .web(WebApplicationType.NONE)
          .run(args);
    } catch (Exception e) {
      // The AOT processor runs this same main at build time and stops it by throwing once it has
      // the context it came for, so catching that would turn a successful processAot into a build
      // failure. A build-time failure belongs at the build with its stack trace anyway.
      if (Boolean.getBoolean("spring.aot.processing")) {
        throw e;
      }
      // Boot reports a failed start through the logger, which here writes to a file — so without
      // this the user gets their prompt back with no output and no reason to think anything ran.
      final var cause = rootCause(e);
      System.err.println(
          "spring-agent could not start: "
              + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
      System.err.println("See " + logFile() + " for the detail.");
      System.exit(1);
    }
  }

  private static Throwable rootCause(final Throwable error) {
    var cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  /** The same default {@code logback-appender-FILE.xml} resolves, and whatever overrode it. */
  private static String logFile() {
    final var configured = System.getenv("LOG_FILE");
    return configured != null && !configured.isBlank()
        ? configured
        : System.getProperty("user.home") + "/.spring-agent/logs/spring-agent-cli.log";
  }
}
