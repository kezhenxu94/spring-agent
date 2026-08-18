package me.kezhenxu94.springagent.core.agent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The scenarios this runtime ships with. */
@Getter
@RequiredArgsConstructor
public enum BuiltInScenarios implements AgentScenario {
  CHAT(true),
  SCHEDULED_TASK(true);

  private final boolean conversationMemory;
}
