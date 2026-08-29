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
   * Whether a run in this scenario is offered {@code tool}, one of the {@code @AgentTool} beans in
   * the context. Every tool by default; override to keep one out of these runs.
   */
  default boolean offers(final Object tool) {
    return true;
  }

  /**
   * Whether a run in this scenario may be offered the {@code AdminTool}s, if the person it belongs
   * to is one. Both halves have to hold: this says the kind of run is eligible, {@code
   * app.ai.admins} says the person is, and {@code AgentToolsProvider} asks them together.
   *
   * <p><b>Off by default, unlike every other question on this interface.</b> An admin tool acts on
   * behalf of the whole deployment rather than of one conversation, so the cost of a scenario
   * forgetting to say no is much larger than the cost of one forgetting to say yes — which is a
   * tool that has to be turned on rather than a power that leaked. A scenario written outside this
   * repository gets the safe answer without having heard of the concept.
   *
   * <p>What "eligible" means in practice is a run a person is present for and asked for. Nothing
   * that fires on a timer or acts on text somebody else wrote qualifies, however admin the identity
   * it runs as: see {@code SituationTriageScenario}, where that identity is routinely an admin and
   * the events it triages are written by strangers.
   */
  default boolean adminTools() {
    return false;
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
