package me.kezhenxu94.springagent.core.config;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults the three prompts the agent speaks with to core's own files, in the workspace's
 * language.
 *
 * <p>These are the largest model-facing text in the system by a wide margin — the system prompt
 * alone is five and a half thousand characters against a few hundred for even the wordiest tool —
 * and until this existed they were the one part that could not be translated at all. A tool's
 * description is an annotation, which {@code LocalizingToolCallingManager} can rewrite on its way
 * to the model; a prompt is a configuration property, and a property has one value. So the language
 * has to be chosen when the value is supplied, which is here.
 *
 * <p>Contributed as the lowest-precedence property source, so an application that states a prompt
 * of its own still wins — including one that wants English on a Chinese workspace. That is also why
 * the applications in this repository no longer carry these prompts in their {@code
 * application.yaml}: a value there would win over this one, and win in one language.
 *
 * <p>Note what the English file is for. It is not a fallback nobody reads: it is the prompt, and
 * the translation beside it is the override. Kept as markdown rather than in the properties bundle
 * for the reason {@link LocalizedPrompt} gives — a hundred lines of instruction folded into a
 * property value is neither writable nor reviewable.
 */
public class PromptDefaults implements EnvironmentPostProcessor, Ordered {

  /** The system prompt's file, without its locale suffix or extension. */
  public static final String SYSTEM_PROMPT = "system-prompt";

  /** What a firing scheduled task says to the model. */
  public static final String SCHEDULED_TASK_PROMPT = "scheduled-task-prompt";

  /** How a subagent is introduced to itself. */
  public static final String SUBAGENT_PROMPT = "subagent-prompt";

  /** Each prompt file and the property it supplies. */
  static final List<String[]> PROMPTS =
      List.of(
          new String[] {SYSTEM_PROMPT, "app.ai.system-prompt"},
          new String[] {SCHEDULED_TASK_PROMPT, "app.ai.scheduled-task-prompt"},
          new String[] {SUBAGENT_PROMPT, "app.ai.subagent-prompt"});

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final var locale = environment.getProperty("app.locale", Locale.class);
    final var defaults = new HashMap<String, Object>();
    for (final var prompt : PROMPTS) {
      defaults.put(prompt[1], LocalizedPrompt.text(prompt[0], locale));
    }
    environment.getPropertySources().addLast(new MapPropertySource("springAgentPrompts", defaults));
  }

  /**
   * Last, so that the property sources this appends after include the ones config data loaded from
   * {@code application.yaml} — which is also what makes {@code app.locale} readable here.
   */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
