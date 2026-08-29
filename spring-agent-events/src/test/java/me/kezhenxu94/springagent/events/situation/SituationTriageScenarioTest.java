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
  @DisplayName("nothing that leaves work behind, changes what the agent can do, or spends money")
  void shouldWithholdTheDangerousTools() {
    assertThat(scenario.offers(mock(ScheduledTaskTool.class))).isFalse();
    assertThat(scenario.offers(mock(SubagentTools.class))).isFalse();
    assertThat(scenario.offers(mock(CredentialTools.class))).isFalse();
    assertThat(scenario.offers(mock(McpServerManagementTools.class))).isFalse();
    assertThat(scenario.offers(mock(SkillManagementTools.class))).isFalse();
    assertThat(scenario.offers(mock(PublishFileTool.class))).isFalse();
    assertThat(scenario.offers(mock(ImageGenerationTools.class))).isFalse();
  }

  @Test
  @DisplayName("and no shell, which is the one place injection is indistinguishable from intrusion")
  void shouldWithholdTheShell() {
    // Losing it costs something real — an agent that could run `kubectl logs` would triage better —
    // but a deployment wanting investigation of that depth should give these runs a read-only MCP
    // server, which is a reach somebody chose, rather than a shell, which is all of them.
    assertThat(scenario.offers(mock(ShellTools.class))).isFalse();
  }

  @Test
  @DisplayName("what is left is what a triage actually needs")
  void shouldOfferTheHarmlessTools() {
    assertThat(scenario.offers(mock(SituationTools.class))).isTrue();
    assertThat(scenario.offers(mock(DateTimeTool.class))).isTrue();
  }
}
