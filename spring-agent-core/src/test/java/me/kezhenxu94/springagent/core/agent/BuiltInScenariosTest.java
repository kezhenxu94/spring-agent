package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
