package me.kezhenxu94.springagent.core.agent;

/**
 * What kind of run this is: what the agent is being asked to do and, from that, how it should
 * behave. {@link BuiltInScenarios} holds the ones shipped here; implement this to add a scenario of
 * your own and hand the instance to {@link AgentRequest}.
 *
 * <p>A scenario decides which tools a run is offered, but it does not hold the list: a tool that
 * belongs only in some runs implements {@code ScenarioGatedTool} and is asked.
 */
public interface AgentScenario {
  /**
   * Whether the run reads the conversation's chat memory and appends its own turn to it. False for
   * a run that must not see, or must not pollute, what a person said in the same conversation.
   */
  boolean conversationMemory();
}
