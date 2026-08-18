package me.kezhenxu94.springagent.core.agent;

/**
 * What kind of run this is: what the agent is being asked to do and, from that, how it should
 * behave. {@link BuiltInScenarios} holds the ones shipped here; implement this to add a scenario of
 * your own and hand the instance to {@link AgentRequest}.
 */
public interface AgentScenario {
  /**
   * Whether the run reads the conversation's chat memory and appends its own turn to it. False for
   * a run that must not see, or must not pollute, what a person said in the same conversation.
   */
  boolean conversationMemory();

  /**
   * Whether a run in this scenario is offered {@code tool}, one of the {@code @AgentTool} beans in
   * the context. Every tool by default; override to keep one out of these runs.
   */
  default boolean offers(final Object tool) {
    return true;
  }
}
