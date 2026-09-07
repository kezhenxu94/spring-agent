package me.kezhenxu94.springagent.core.agent;

/**
 * What kind of run this is: what the agent is being asked to do and, from that, how it should
 * behave. {@link BuiltInScenarios} holds the ones shipped here; implement this to add a scenario of
 * your own and hand the instance to {@link AgentRequest}.
 */
public interface AgentScenario {
  /**
   * Whether the run reads the conversation's chat memory and appends its own turn to it. It does by
   * default; override to false for a run that must not see, or must not pollute, what a person said
   * in the same conversation.
   */
  default boolean conversationMemory() {
    return true;
  }

  /**
   * Whether a run in this scenario is offered any tools at all. It is by default; override to false
   * for a run that must turn one prompt into one answer with nothing in between.
   *
   * <p>Coarser than {@link #offers} and not a shorthand for it: {@code offers} rules on the
   * {@code @AgentTool} beans alone, while everything else a run is composed of — the filesystem and
   * todo tools, the ask, the skills tool, the memory tools, the user's MCP servers and the
   * application-wide ones — arrives from elsewhere and no per-tool ruling reaches it. Saying no
   * here is what keeps all of it out, and it is also what spares the run the MCP fan-out, which
   * dials out to every server the user can reach before the model is asked anything.
   */
  default boolean tools() {
    return true;
  }

  /**
   * Whether a run in this scenario is offered {@code tool}, one of the {@code @AgentTool} beans in
   * the context. Every tool by default; override to keep one out of these runs.
   */
  default boolean offers(final Object tool) {
    return true;
  }

  /**
   * Whether the run consults the knowledge base automatically, retrieving what the user's, group's
   * and tenant's knowledge has to say about the message before the model sees it.
   *
   * <p>It does by default; override to false for a run whose prompt is not a question anyone has
   * knowledge about, since retrieval costs an embedding of every message whether or not anything
   * comes back. Turning it off here is independent of the deployment-wide switch and of whether any
   * {@code KnowledgeBase} implementation is installed at all — all three have to agree before
   * anything is retrieved.
   */
  default boolean knowledgeRetrieval() {
    return true;
  }
}
