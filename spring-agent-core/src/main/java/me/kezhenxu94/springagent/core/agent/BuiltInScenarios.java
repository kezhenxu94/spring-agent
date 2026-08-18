package me.kezhenxu94.springagent.core.agent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;

/** The scenarios this runtime ships with. */
@Getter
@RequiredArgsConstructor
public enum BuiltInScenarios implements AgentScenario {
  CHAT(true),
  SCHEDULED_TASK(true) {
    @Override
    public boolean offers(final Object tool) {
      // A run that fires on a schedule must not be able to schedule more work, which is how one
      // task becomes a growing pile of them.
      return !(tool instanceof ScheduledTaskTool);
    }
  };

  private final boolean conversationMemory;
}
