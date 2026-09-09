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
    /**
     * No conversation memory, in either direction, and this is what keeps a repeating task honest.
     * A firing speaks in the conversation the task was created in, so reading it back put the
     * previous occurrence in front of the model — the same prompt, word for word, already answered
     * with "done, here is the report". The likeliest thing to do with that is to agree that the
     * work is done and hand yesterday's result back as today's, and no wording beats an identical
     * prior turn sitting in the window. Each firing therefore starts with nothing behind it but its
     * own task text, which is the whole of what it was asked to do.
     *
     * <p>Nor does it write: a report nobody asked for, appended to a person's thread every morning,
     * is a history their next question is answered against. The report still reaches them — it is
     * the run's reply, put on the thread the task was created in — it is only the model's memory
     * that a firing leaves alone.
     *
     * <p>Every surface already assumes this. Each of them registers a question handler only for
     * {@link #CHAT}, on the grounds that an answer arriving later would have no history to rejoin,
     * so a firing cannot ask anything and nothing is waiting on a turn that was never written.
     */
    @Override
    public boolean conversationMemory() {
      return false;
    }

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
  },

  /**
   * A single request answered in isolation — no conversation memory, no knowledge retrieval, no
   * tools. For a caller that wants one prompt turned into one answer (a summary, a classification,
   * a translation) with no chance of the run reaching for a tool, remembering a past turn, or
   * pulling in retrieved context nobody asked for.
   */
  ONE_OFF {
    @Override
    public boolean conversationMemory() {
      return false;
    }

    @Override
    public boolean knowledgeRetrieval() {
      return false;
    }

    @Override
    public boolean tools() {
      return false;
    }
  }
}
