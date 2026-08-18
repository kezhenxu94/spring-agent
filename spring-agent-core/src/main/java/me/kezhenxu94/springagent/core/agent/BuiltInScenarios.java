package me.kezhenxu94.springagent.core.agent;

import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;

/** The scenarios this runtime ships with. Both take part in the conversation's chat memory. */
public enum BuiltInScenarios implements AgentScenario {
  CHAT,
  SCHEDULED_TASK {
    @Override
    public boolean offers(final Object tool) {
      // A run that fires on a schedule must not be able to schedule more work, which is how one
      // task becomes a growing pile of them.
      return !(tool instanceof ScheduledTaskTool);
    }
  }
}
