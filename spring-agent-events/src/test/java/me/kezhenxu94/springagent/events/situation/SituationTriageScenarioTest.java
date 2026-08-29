package me.kezhenxu94.springagent.events.situation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.tools.DateTimeTool;
import me.kezhenxu94.springagent.core.tools.ImageGenerationTools;
import me.kezhenxu94.springagent.core.tools.PublishFileTool;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SkillManagementTools;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import me.kezhenxu94.springagent.core.tools.credentials.CredentialTools;
import me.kezhenxu94.springagent.core.tools.mcp.McpServerManagementTools;
import me.kezhenxu94.springagent.events.tools.SituationTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.ShellTools;

/**
 * What a run started by the system may do, as opposed to one a person asked for.
 *
 * <p>Its input was written by somebody who does not know the agent will read it, and possibly by
 * somebody who does and is hostile — anyone can open an issue whose body gives the agent
 * instructions. So the narrowing here is the point of the class rather than a precaution around it,
 * and each withheld tool is worth asserting by name: adding a tool to the codebase does not add it
 * to any list, so the failure mode is silent.
 */
class SituationTriageScenarioTest {

  private final SituationTriageScenario scenario = new SituationTriageScenario();

  @Test
  @DisplayName("no conversation memory, in either direction")
  void shouldNotUseConversationMemory() {
    // A situation looked at twenty times would otherwise carry a transcript of twenty turns,
    // trimmed
    // from the front — losing the earliest evidence, the part that says when this began. And these
    // runs share a chat with real conversations, so writing to that memory would put turns nobody
    // said into the history a person's next question is answered against.
    assertThat(scenario.conversationMemory()).isFalse();
  }

  @Test
  @DisplayName("the knowledge base is still consulted")
  void shouldRetrieveKnowledge() {
    // "Have we seen this before" is most of what makes a triage useful, and a tenant's runbooks are
    // exactly what the knowledge base holds.
    assertThat(scenario.knowledgeRetrieval()).isTrue();
  }

  @Test
  @DisplayName("no scheduler, so one alert cannot become a growing pile of tasks")
  void shouldWithholdTheScheduler() {
    // Out for the reason it is out of SCHEDULED_TASK: work left behind outlives the turn that asked
    // for it, and an unattended run has nobody to answer for it.
    assertThat(scenario.offers(mock(ScheduledTaskTool.class))).isFalse();
  }

  @Test
  @DisplayName("everything else, including the tools that make an investigation worth anything")
  void shouldOfferEverythingElse() {
    // Deliberately not an allow-list. offers() is consulted about the @AgentTool beans and nothing
    // else — every MCP callback reaches the run whatever this says — so withholding these would
    // cost
    // the agent the ability to look at what it is being asked about while closing nothing. What
    // protects the deployment is the dedicated identity, the prompt framing, and
    // app.ai.tools.shell.
    assertThat(scenario.offers(mock(SubagentTools.class))).isTrue();
    assertThat(scenario.offers(mock(CredentialTools.class))).isTrue();
    assertThat(scenario.offers(mock(McpServerManagementTools.class))).isTrue();
    assertThat(scenario.offers(mock(SkillManagementTools.class))).isTrue();
    assertThat(scenario.offers(mock(PublishFileTool.class))).isTrue();
    assertThat(scenario.offers(mock(ImageGenerationTools.class))).isTrue();
    assertThat(scenario.offers(mock(ShellTools.class))).isTrue();
  }

  @Test
  @DisplayName("what is left is what a triage actually needs")
  void shouldOfferTheHarmlessTools() {
    assertThat(scenario.offers(mock(SituationTools.class))).isTrue();
    assertThat(scenario.offers(mock(DateTimeTool.class))).isTrue();
  }
}
