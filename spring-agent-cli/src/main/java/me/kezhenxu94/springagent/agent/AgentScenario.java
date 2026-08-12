package me.kezhenxu94.springagent.agent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AgentScenario {
  CHAT(true),
  SCHEDULED_TASK(false),
  ALL(true);

  private final boolean conversationMemory;
}
