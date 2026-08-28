package me.kezhenxu94.springagent.core.config;

import java.util.Map;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.Subagent;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies the settings under {@code app.ai.tools} that a tool needs a value for, so that an
 * application which merely puts core on its classpath gets the same behaviour as the applications
 * in this repository rather than whatever the binder happened to leave.
 *
 * <p>Which the record's own fallbacks were already doing — and that is the problem this solves. A
 * default written twice, once as a constant read when the property is absent and once as the
 * literal in an {@code application.yaml} naming the environment variable, is a default that
 * silently diverges: raising the concurrent-subagent limit in the server's yaml left the command
 * line, which states no limit of its own, running the old one. Here the value is stated once and
 * both read it.
 *
 * <p>The yaml entries stay, because they are what gives each setting an environment variable to
 * override it with, and they are where the reasoning behind the value is written down. They now
 * name the same numbers as the constants below; keep them that way.
 *
 * <p>Two settings in this section are deliberately absent. {@code app.ai.tools.shell.type} is not
 * defaulted here because unset already means no shell, and {@code ShellBackendResolver} reads it
 * before any bean exists. {@code app.ai.tools.publish-file.base-url} is not defaulted because there
 * is no address core can guess for another application's deployment — a wrong one hands out links
 * that go nowhere, so the {@code @Value} on {@code PublishFileTool} is left to fail startup.
 *
 * <p>Contributed as the lowest-precedence property source, so anything set anywhere else — a yaml,
 * an environment variable, a command line — still wins.
 */
public class ToolDefaults implements EnvironmentPostProcessor, Ordered {

  static final String ASK_USER_QUESTION_ENABLED = "app.ai.tools.ask-user-question.enabled";

  static final String ASK_USER_QUESTION_TTL = "app.ai.tools.ask-user-question.ttl";

  static final String SUBAGENT_MAX_CONCURRENT = "app.ai.tools.subagent.max-concurrent";

  static final String SUBAGENT_WAIT_POLL = "app.ai.tools.subagent.wait-poll";

  static final String SUBAGENT_WAIT_TIMEOUT = "app.ai.tools.subagent.wait-timeout";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentToolDefaults",
                Map.of(
                    ASK_USER_QUESTION_ENABLED,
                    String.valueOf(AskUserQuestion.DEFAULT_ENABLED),
                    // ISO-8601, which is what Duration.toString writes and what Spring's duration
                    // conversion reads, so the constant can be handed over without a format of its
                    // own in between.
                    ASK_USER_QUESTION_TTL,
                    AskUserQuestion.DEFAULT_TTL.toString(),
                    SUBAGENT_MAX_CONCURRENT,
                    String.valueOf(Subagent.DEFAULT_MAX_CONCURRENT),
                    SUBAGENT_WAIT_POLL,
                    Subagent.DEFAULT_WAIT_POLL.toString(),
                    SUBAGENT_WAIT_TIMEOUT,
                    Subagent.DEFAULT_WAIT_TIMEOUT.toString())));
  }

  /**
   * Last, so that the property sources this appends after include the ones config data loaded from
   * {@code application.yaml}.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
