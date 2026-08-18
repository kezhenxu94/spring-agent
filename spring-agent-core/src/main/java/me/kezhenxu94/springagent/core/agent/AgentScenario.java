package me.kezhenxu94.springagent.core.agent;

/**
 * What kind of run this is: what the agent is being asked to do and, from that, how it should
 * behave. {@link BuiltInScenarios} holds the ones shipped here; implement this to add a scenario of
 * your own and hand the instance to {@link AgentRequest}.
 *
 * <p>A run in a scenario of your own is offered only the tools annotated {@code
 * BuiltInScenarios.ALL}: an annotation attribute cannot have an interface type, so {@code
 * AgentTool#scenario()} can only name constants of the enum. The request is the extension point,
 * the annotation is not.
 */
public interface AgentScenario {
  /**
   * Whether the run reads the conversation's chat memory and appends its own turn to it. False for
   * a run that must not see, or must not pollute, what a person said in the same conversation.
   */
  boolean conversationMemory();
}
