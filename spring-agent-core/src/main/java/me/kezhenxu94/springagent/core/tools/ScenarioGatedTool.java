package me.kezhenxu94.springagent.core.tools;

import me.kezhenxu94.springagent.core.agent.AgentScenario;

/**
 * A tool that does not belong in every run. {@link AgentToolsProvider} asks each {@code @AgentTool}
 * bean that implements this whether it belongs in the run being composed; a tool that does not
 * implement it is offered to every scenario.
 *
 * <p>Gating lives here rather than on the {@code @AgentTool} annotation because an annotation
 * attribute cannot have an interface type: it could only ever name the scenarios shipped here,
 * which would leave someone who added a scenario of their own unable to say which runs their tools
 * belong in.
 */
public interface ScenarioGatedTool {

  /** Whether a run in {@code scenario} is offered this tool. */
  boolean appliesTo(AgentScenario scenario);
}
