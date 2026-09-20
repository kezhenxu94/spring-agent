package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import me.kezhenxu94.springagent.core.knowledge.KnowledgeAdminTools;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBaseTools;
import me.kezhenxu94.springagent.core.memory.MemoryTools;
import me.kezhenxu94.springagent.core.tools.FiringScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SkillManagementTools;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import me.kezhenxu94.springagent.core.tools.VisionTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class BuiltInScenariosTest {

  @Test
  void oneOffDisablesMemoryKnowledgeAndTools() {
    assertThat(BuiltInScenarios.ONE_OFF.conversationMemory()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.knowledgeRetrieval()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.tools()).isFalse();
  }

  @Test
  @DisplayName(
      "a firing reads no conversation, so it cannot mistake the last occurrence for this one")
  void aScheduledTaskHasNoConversationMemory() {
    // The bug this exists for: a daily task read back yesterday's firing — its own prompt, already
    // answered with a report — and reported yesterday's result again rather than doing the work.
    assertThat(BuiltInScenarios.SCHEDULED_TASK.conversationMemory()).isFalse();
    // While a chat is the case memory is for, and the default nothing has to say.
    assertThat(BuiltInScenarios.CHAT.conversationMemory()).isTrue();
  }

  @Test
  void everyOtherScenarioIsOfferedTools() {
    // tools() is the coarse gate, and only ONE_OFF closes it. A scenario that turned it off by
    // accident would be composed with nothing at all — no filesystem, no MCP, no memory tools —
    // and the symptom is a model that answers everything from its own head, which reads as a bad
    // model rather than as a misconfigured run.
    assertThat(BuiltInScenarios.CHAT.tools()).isTrue();
    assertThat(BuiltInScenarios.SCHEDULED_TASK.tools()).isTrue();
    assertThat(BuiltInScenarios.SUBAGENT.tools()).isTrue();
  }

  @Test
  @DisplayName("a knowledge-base run is offered the two stores and the eyes to read a question")
  void knowledgeBaseOffersOnlyWhatAnswersFromWhatIsStored() {
    final var scenario = BuiltInScenarios.KNOWLEDGE_BASE;
    assertThat(scenario.offers(mock(KnowledgeBaseTools.class))).isTrue();
    assertThat(scenario.offers(mock(KnowledgeAdminTools.class))).isTrue();
    assertThat(scenario.offers(mock(MemoryTools.class))).isTrue();
    // A question can arrive as a screenshot, and a run that cannot look has nothing to search for.
    assertThat(scenario.offers(mock(VisionTools.class))).isTrue();
  }

  @Test
  @DisplayName("and nothing else, by name, because a tool added later joins no list")
  void knowledgeBaseWithholdsEverythingElse() {
    // Named one by one rather than asserted in bulk for the reason SituationTriageScenarioTest
    // gives: adding a tool to this codebase adds it to nothing, so the failure mode is silent. An
    // allow-list is the safe side of that — a new tool is withheld until somebody says otherwise —
    // and these are the ones whose reaching a /kb run would defeat the point of asking for one.
    final var scenario = BuiltInScenarios.KNOWLEDGE_BASE;
    assertThat(scenario.offers(mock(SubagentTools.class))).isFalse();
    assertThat(scenario.offers(mock(ScheduledTaskTool.class))).isFalse();
    assertThat(scenario.offers(mock(FiringScheduledTaskTool.class))).isFalse();
    assertThat(scenario.offers(mock(SkillManagementTools.class))).isFalse();
    // And every tool that arrives already built — an MCP server's, a skill's — which reach a run
    // through the other overload and would otherwise be the whole internet on a /kb turn.
    assertThat(scenario.offers(mock(ToolCallback.class))).isFalse();
  }

  @Test
  @DisplayName("only the scenarios a person may ask for carry a memo")
  void memosAreDeclaredByTheScenariosAPersonMayAskFor() {
    // The gate is the declaration itself: ScenarioMemos reads memoNames() and nothing else, so a
    // scenario that names no word cannot be summoned by typing at the agent. A memo on SUBAGENT
    // would let somebody start a run with no conversation memory and no surface waiting on it.
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.memoNames())
        .containsExactlyInAnyOrder("kb", "knowledge-base", "knowledge_base");
    assertThat(BuiltInScenarios.CHAT.memoNames()).isEmpty();
    assertThat(BuiltInScenarios.SUBAGENT.memoNames()).isEmpty();
    assertThat(BuiltInScenarios.SCHEDULED_TASK.memoNames()).isEmpty();
    assertThat(BuiltInScenarios.ONE_OFF.memoNames()).isEmpty();
  }

  @Test
  @DisplayName("a surface draws, and may ask, only for a run somebody is waiting on")
  void onlyAChatRunIsInteractive() {
    // What this decides on each surface: whether a card, a reply or a gutter is drawn for the run,
    // whether the run is abandoned when that could not be put on screen, and whether a question
    // handler is registered — which is what decides whether the agent is offered the ask at all.
    assertThat(BuiltInScenarios.CHAT.interactive()).isTrue();
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.interactive()).isTrue();
    assertThat(BuiltInScenarios.SUBAGENT.interactive()).isFalse();
    assertThat(BuiltInScenarios.SCHEDULED_TASK.interactive()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.interactive()).isFalse();
  }

  @Test
  @DisplayName("a knowledge-base run is a turn in a real conversation, and retrieves by default")
  void knowledgeBaseKeepsMemoryAndRetrieval() {
    // Both deliberate. It is said into the same thread as everything around it, so leaving it out
    // of the history would have the next question answered as if it had never been asked; and
    // automatic retrieval is the whole point of the run rather than an augmentation of it.
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.conversationMemory()).isTrue();
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.knowledgeRetrieval()).isTrue();
    assertThat(BuiltInScenarios.KNOWLEDGE_BASE.tools()).isTrue();
  }
}
