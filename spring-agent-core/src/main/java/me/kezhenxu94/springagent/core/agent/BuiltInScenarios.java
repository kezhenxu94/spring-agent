package me.kezhenxu94.springagent.core.agent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The scenarios this runtime ships with. */
@Getter
@RequiredArgsConstructor
public enum BuiltInScenarios implements AgentScenario {
  CHAT(true),
  SCHEDULED_TASK(true),
  // Not a scenario a run can be in: the wildcard @AgentTool uses to mean "offered to every run".
  ALL(true);

  private final boolean conversationMemory;
}
