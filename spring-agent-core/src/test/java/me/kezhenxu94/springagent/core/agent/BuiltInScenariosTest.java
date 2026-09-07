package me.kezhenxu94.springagent.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuiltInScenariosTest {

  @Test
  void oneOffDisablesMemoryKnowledgeAndTools() {
    assertThat(BuiltInScenarios.ONE_OFF.conversationMemory()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.knowledgeRetrieval()).isFalse();
    assertThat(BuiltInScenarios.ONE_OFF.tools()).isFalse();
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
