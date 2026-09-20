package me.kezhenxu94.springagent.core.agent;

import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

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
   * <p>Coarser than {@link #offers} and not a synonym for it, though the two now rule on the same
   * set. What this decides that {@code offers} cannot is <i>the cost of finding out</i>: saying no
   * here is answered before anything is built, so the run is spared the MCP fan-out, which dials
   * out to every server the user can reach before the model is asked anything. An {@code offers}
   * that refuses everything composes the same empty run, having paid for the handshakes first.
   */
  default boolean tools() {
    return true;
  }

  /**
   * Whether a run in this scenario is offered {@code tool}, one of the plain tool objects composed
   * into it — an {@code @AgentTool} bean, the file-system tools, the three search tools, the todo
   * tool, the ask. Every tool by default; override to keep one out of these runs.
   *
   * <p>Asked about everything a run is composed of rather than about the {@code @AgentTool} beans
   * alone, which is what makes an allow-list mean what it says: a scenario naming the two tools it
   * wants gets exactly those, and does not silently also get a sandbox and every MCP server the
   * user has registered.
   */
  default boolean offers(final Object tool) {
    return true;
  }

  /**
   * Whether a run in this scenario is offered {@code tool}, one that arrives already built as a
   * callback: an MCP server's tools, a skill, the ask on the surfaces whose answer comes later.
   *
   * <p>A second method rather than the one above because there is no type to tell these apart —
   * every one of them is a {@link ToolCallback}, whatever it came from — so the only thing to rule
   * on is {@code tool.getToolDefinition().name()}. An MCP server's tools are named for the server
   * (see {@code ServerNameToolPrefixGenerator}) and a skill's are prefixed {@code skill_}, which is
   * what makes a ruling by name possible at all.
   *
   * <p>It delegates by default, so a scenario that wants none of them writes {@link
   * #offers(Object)} alone and is answered for both. Override this one only to keep some callbacks
   * and not others.
   *
   * <p><b>Java picks an overload statically.</b> A call site holding one of these as an {@code
   * Object} gets {@link #offers(Object)} instead, with no warning and no way to tell afterwards. So
   * a composition has to filter its typed {@code List<ToolCallback>} apart from its plain tool
   * objects, and must never merge the two before filtering — see {@code
   * AgentToolsProvider.composeWith}.
   */
  default boolean offers(final ToolCallback tool) {
    return offers((Object) tool);
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

  /**
   * Whether somebody is on the other end of this run, waiting to read its answer.
   *
   * <p>What a surface does with it: whether to draw a card, a stop button or a gutter for the run,
   * whether to abandon it when that rendering could not be put on screen, and whether to register a
   * question handler — which is what decides whether the agent is offered the ask at all.
   *
   * <p>False by default, and that is the safe way round. A firing, a subagent and a triage run each
   * answer to something other than a person watching, so a scenario that says nothing is treated as
   * one nobody is waiting on: at worst its progress goes unrendered, where the other mistake is an
   * unattended run asking a question into a void and stopping there.
   */
  default boolean interactive() {
    return false;
  }

  /**
   * The words a person may select this scenario by from a chat, lower case and without the leading
   * slash — {@code Set.of("kb", "knowledge-base")} and a message saying {@code /kb}. Several
   * because one spelling is never everybody's; matching is case-insensitive, so list each only
   * once.
   *
   * <p>Empty by default, and that is the whole of the gate: a scenario contributes a memo only by
   * naming one, so {@code SUBAGENT} and {@code SCHEDULED_TASK} cannot be summoned by typing at the
   * agent. Whoever adds a memo is stating that a run of this kind is something a person may ask for
   * — which is not true of every scenario, and is a decision the scenario is the right place to
   * make.
   *
   * <p>{@code ScenarioMemos} is what reads these, and it refuses to start where two scenarios claim
   * the same word.
   */
  default Set<String> memoNames() {
    return Set.of();
  }
}
