package me.kezhenxu94.springagent.core.agent;

import me.kezhenxu94.springagent.core.tools.FiringScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SubagentTools;

/** The scenarios this runtime ships with. */
public enum BuiltInScenarios implements AgentScenario {
  CHAT {
    @Override
    public boolean offers(final Object tool) {
      // Nothing is firing, so there is no task for the firing tools to act on. They would refuse if
      // called; keeping them out spends no tokens describing tools that can only say no.
      return !(tool instanceof FiringScheduledTaskTool);
    }
  },
  SCHEDULED_TASK {
    @Override
    public boolean offers(final Object tool) {
      // A run that fires on a schedule must not be able to schedule more work, which is how one
      // task becomes a growing pile of them. What it may do is end or re-arm the one task it is a
      // firing of, which is FiringScheduledTaskTool and is offered.
      return !(tool instanceof ScheduledTaskTool);
    }
  },
  /**
   * A run another run asked for, whose answer is a tool result rather than a reply to anybody. It
   * is the same agent with the same tools, so it can be given work of real size — and it is told,
   * in {@code app.ai.subagent-prompt}, that its final message is the whole of what its caller gets.
   */
  SUBAGENT {
    /**
     * No conversation memory in either direction. A subagent is given its task in full by whoever
     * started it, so reading the thread would only spend tokens on a conversation it is not part
     * of; and writing to it would put a turn nobody said into the history the user's next question
     * is answered against.
     */
    @Override
    public boolean conversationMemory() {
      return false;
    }

    @Override
    public boolean offers(final Object tool) {
      // No subagents of its own, which is what caps the depth at one: with no counter to get wrong,
      // a run cannot fan out into a tree whose size nothing bounds. The scheduler is out for the
      // reason it is out of a scheduled task — work left behind outlives the turn that asked for
      // it,
      // and here there is nobody to answer for it.
      return !(tool instanceof SubagentTools)
          && !(tool instanceof ScheduledTaskTool)
          && !(tool instanceof FiringScheduledTaskTool);
    }
  }
}
