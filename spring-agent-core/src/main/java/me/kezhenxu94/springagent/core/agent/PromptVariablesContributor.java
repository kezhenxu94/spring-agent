package me.kezhenxu94.springagent.core.agent;

import java.util.Map;

/**
 * System-prompt variables a surface contributes to every run, whoever started it.
 *
 * <p>The counterpart of {@link AgentRequest#promptVariables()} for what a surface knows before any
 * request exists — how its own client renders what the model writes, say. Declared as a
 * {@code @Bean}, exactly as an {@link AgentResponseListener} is to take part in a run it did not
 * initiate: a scheduled task fires with no surface in the picture and its answer still lands on
 * one, so it still has to be told how to write for it.
 *
 * <p>Only fills slots nobody else filled: the request's own variables win over these, and core's
 * identity variables win over both.
 */
public interface PromptVariablesContributor {

  /**
   * The variables for this run. The names have to be ones the configured system prompt knows —
   * referencing a name the template does not declare is ignored, while a template slot nothing
   * fills fails the render, which is why core defaults the optional ones itself.
   */
  Map<String, Object> variables(AgentRequest request);
}
