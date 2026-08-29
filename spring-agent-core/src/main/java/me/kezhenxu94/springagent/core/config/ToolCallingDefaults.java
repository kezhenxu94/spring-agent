package me.kezhenxu94.springagent.core.config;

import java.util.Map;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Raises the ceilings Spring AI puts on a single turn's tool calls, for the same reason {@link
 * ToolDefaults} exists: an application that merely puts core on its classpath gets the numbers this
 * agent needs rather than the ones a library picked for a chat assistant.
 *
 * <p>Upstream allows a tool 40 calls and a turn 150 across all tools ({@code
 * DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL} and {@code DEFAULT_MAX_TOTAL_TOOL_CALLS}).
 * Those are generous for one question answered by one lookup and thin for what runs here: a triage
 * run reads an event, searches the knowledge base, walks a cluster with the shell tools and writes
 * back, and the same tool is the right one many times over — reading a file, listing a namespace,
 * grepping. Hitting a limit does not degrade the answer, it ends the turn mid-thought, so the
 * limits are set where they still catch a model looping on a tool that keeps failing and no longer
 * catch work.
 *
 * <p>Both numbers are the same, deliberately: the per-tool limit exists to stop one tool from
 * spinning, and a turn that spends its whole budget on one tool has already hit the total. Raising
 * only the per-tool one would have changed nothing, since 150 across all tools would still have
 * stopped the turn first.
 *
 * <p>Contributed as the lowest-precedence property source, so a deployment still overrides either
 * one — including with {@code -1}, which Spring AI reads as no limit at all.
 */
public class ToolCallingDefaults implements EnvironmentPostProcessor, Ordered {

  /** How many times one tool may be called in a turn, unless that tool is named individually. */
  public static final int MAX_CALLS_PER_TOOL = 200;

  /** How many tool calls a turn may make across every tool together. */
  public static final int MAX_TOTAL_TOOL_CALLS = 200;

  static final String MAX_CALLS_PER_TOOL_DEFAULT_KEY =
      ToolCallingProperties.CONFIG_PREFIX + ".limits.max-calls-per-tool-default";

  static final String MAX_TOTAL_TOOL_CALLS_KEY =
      ToolCallingProperties.CONFIG_PREFIX + ".limits.max-total-tool-calls";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "springAgentToolCallingDefaults",
                Map.of(
                    MAX_CALLS_PER_TOOL_DEFAULT_KEY,
                    String.valueOf(MAX_CALLS_PER_TOOL),
                    MAX_TOTAL_TOOL_CALLS_KEY,
                    String.valueOf(MAX_TOTAL_TOOL_CALLS))));
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
