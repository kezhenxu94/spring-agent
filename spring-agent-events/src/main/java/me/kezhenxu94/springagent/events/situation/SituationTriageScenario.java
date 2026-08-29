package me.kezhenxu94.springagent.events.situation;

import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;

/**
 * A run the system started about something it noticed, rather than one a person asked for.
 *
 * <p>The same agent with the same tools, less the one that would leave work behind. What differs
 * from a chat run is not what it may do but what it is told and what it remembers: it is briefed
 * from the situation rather than from a conversation, and it is told that everything it has been
 * shown was written by somebody else.
 *
 * <p><b>Where the safety actually comes from.</b> This run's input was written by whoever caused
 * the event, and anyone who can open an issue can write it — so it is worth being precise about
 * what protects the deployment, because it is not this class:
 *
 * <ul>
 *   <li>{@code app.events.sources.<name>.owner-user-id}, which must name an identity of the agent's
 *       own and never a person's. A run assumes it, and with it that identity's file sandbox and
 *       personal MCP servers. {@code SituationSweeper} refuses to evaluate a source without one and
 *       says so at startup.
 *   <li>the prompt, which says at length that the observed text is data to be assessed and never
 *       instructions to follow, and the fence {@code SituationBrief} puts around it.
 *   <li>whatever the deployment chose to give the agent at all. A shell exists only where {@code
 *       app.ai.tools.shell.type} says so, and it defaults to {@code none}.
 * </ul>
 *
 * <p>An allow-list here would add little to that and cost something real. {@link #offers} is
 * consulted about the {@code @AgentTool} beans and nothing else — the file-system tools, the todo
 * tool, the skills tool and every MCP callback, including the application-wide servers under {@code
 * spring.ai.mcp.client.*}, reach every run whatever this says. So a scenario that withheld the
 * shell would still hand a run whatever reach the deployment's MCP servers have, while losing the
 * agent the ability to look at the thing it is being asked about; an alert triaged without being
 * able to read a log is mostly guesswork.
 *
 * <p>The ask tool needs no mention. A triage run is a background run, which makes {@code
 * AgentRunRegistry.addQuestionHandler} a no-op, so no handler is registered and the tool is never
 * composed in — there is nobody on the other end of a question about an alert.
 */
public final class SituationTriageScenario implements AgentScenario {

  /**
   * No conversation memory, in either direction.
   *
   * <p>A situation is long-lived and looked at repeatedly, so a transcript of those looks would
   * grow without bound and then be silently trimmed from the front — losing the earliest evidence,
   * which is the part that says when this started. What continuity there is, is deliberate instead
   * of incidental: the agent's own last assessment is stored on the situation and rendered back
   * into the next prompt, where it can be read, edited and inspected in the database.
   *
   * <p>The other direction matters as much for the chat case. These runs share a chat with real
   * conversations, and writing "I looked at this and said nothing" into the memory of a group chat
   * would put turns nobody said into the history a person's next question is answered against.
   */
  @Override
  public boolean conversationMemory() {
    return false;
  }

  /**
   * Retrieval stays on. "Have we seen this before" is most of what makes a triage useful, and a
   * tenant's runbooks are exactly the kind of thing the knowledge base holds.
   */
  @Override
  public boolean knowledgeRetrieval() {
    return true;
  }

  /**
   * Everything but the scheduler, which is out for the reason it is out of {@code SCHEDULED_TASK}:
   * work left behind outlives the turn that asked for it, and an unattended run has nobody to
   * answer for it. A situation that keeps recurring would otherwise be able to leave a scheduled
   * task behind on every look, which is how one alert becomes a growing pile of them.
   */
  @Override
  public boolean offers(final Object tool) {
    return !(tool instanceof ScheduledTaskTool);
  }
}
