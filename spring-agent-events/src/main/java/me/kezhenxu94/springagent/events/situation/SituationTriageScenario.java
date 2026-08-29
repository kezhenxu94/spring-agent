package me.kezhenxu94.springagent.events.situation;

import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.tools.ImageGenerationTools;
import me.kezhenxu94.springagent.core.tools.PublishFileTool;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SkillManagementTools;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import me.kezhenxu94.springagent.core.tools.credentials.CredentialTools;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerManagementTools;
import org.springaicommunity.agent.tools.ShellTools;

/**
 * A run the system started about something it noticed, rather than one a person asked for.
 *
 * <p>Its input was written by somebody who does not know the agent will read it — an alerting rule,
 * an issue author, people talking in a chat — and may have been written by somebody who does and is
 * hostile. Anyone can open an issue whose body gives the agent instructions. So this scenario is
 * narrower than {@link me.kezhenxu94.springagent.core.agent.BuiltInScenarios#CHAT}, which offers
 * everything, and the narrowing is the point rather than a precaution.
 *
 * <p><b>What this cannot narrow, and what follows from that.</b> {@link #offers} is consulted about
 * the {@code @AgentTool} beans and about nothing else: the file-system tools, the todo tool, the
 * skills tool and every MCP callback — including the application-wide servers configured under
 * {@code spring.ai.mcp.client.*} — are added to every run regardless of scenario. A deployment that
 * gives the agent a GitHub MCP server therefore gives it to these runs too, with whatever write
 * access that server has. That is partly the intention here, since answering an issue is one of the
 * things this feature is for, but it means the reach of a triage run is set by how the deployment
 * is configured and not by this class. Two things follow, and neither is optional:
 *
 * <ul>
 *   <li>{@code app.events.sources.<name>.owner-user-id} must be an identity of the agent's own,
 *       never a person's. A run assumes it, and with it that person's file-system sandbox and their
 *       personal MCP servers — so pointing these runs at a human hands attacker-authored text their
 *       workspace and their credentials.
 *   <li>The prompt has to say that the observed text is data and not instruction. That is why
 *       {@code app.events.triage-prompt} says so at length, and why it is worth reading before
 *       editing.
 * </ul>
 *
 * <p>The ask tool needs no mention here. A triage run is a background run, which makes {@code
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
   * Everything that acts on the deployment itself rather than on the situation is withheld.
   *
   * <p>Three groups, for three reasons. Scheduling and subagents leave work behind that outlives
   * the turn, with nobody to answer for it — the same reasoning that keeps them out of {@code
   * SCHEDULED_TASK} and {@code SUBAGENT}. Credentials, MCP server management and skill management
   * change what the agent can do next, and a run whose input is a stranger's text must not be able
   * to grant itself anything. Publishing hands out URLs, and image generation spends money, neither
   * of which is a step towards deciding whether an alert matters.
   *
   * <p>Shell tools are withheld by name for the same reason, and losing them costs something real:
   * an agent that could run {@code kubectl logs} would triage an alert better than one that cannot.
   * It is not a close call, though — a shell is the one tool where a successful prompt injection is
   * indistinguishable from an intrusion. A deployment that wants investigation of that depth should
   * give these runs a read-only MCP server, which is a reach somebody chose, rather than a shell,
   * which is all of them.
   */
  @Override
  public boolean offers(final Object tool) {
    return !(tool instanceof ScheduledTaskTool)
        && !(tool instanceof SubagentTools)
        && !(tool instanceof CredentialTools)
        && !(tool instanceof McpServerManagementTools)
        && !(tool instanceof SkillManagementTools)
        && !(tool instanceof PublishFileTool)
        && !(tool instanceof ImageGenerationTools)
        && !isShellTool(tool);
  }

  /**
   * The local backend by type, since it is Spring AI's own {@code ShellTools} and this module
   * already compiles against that library. The other two by class name, because each lives in its
   * own optional module: naming those types would make this module depend on both, and the answer
   * has to be the same whether they are on the classpath or not.
   */
  private static boolean isShellTool(final Object tool) {
    if (tool instanceof ShellTools) {
      return true;
    }
    final var name = tool.getClass().getName();
    return name.equals("me.kezhenxu94.springagent.tools.shell.docker.DockerShellTools")
        || name.equals("me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellTools");
  }
}
