package me.kezhenxu94.springagent.core.agent;

import java.util.Set;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeAdminTools;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBaseTools;
import me.kezhenxu94.springagent.core.memory.MemoryTools;
import me.kezhenxu94.springagent.core.tools.FiringScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import me.kezhenxu94.springagent.core.tools.VisionTools;

/** The scenarios this runtime ships with. */
public enum BuiltInScenarios implements AgentScenario {
  CHAT {
    @Override
    public boolean interactive() {
      return true;
    }

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
   *
   * <p>A person can ask for one too, with {@code /mini}, and the thing to know before typing it is
   * that <b>the turn leaves no trace in the conversation</b>: {@link #conversationMemory()} is
   * false in both directions, so neither the question nor the answer is there for the next turn to
   * refer back to. That is the point rather than a wart — it is what keeps a quick aside out of the
   * history a real question is answered against — but "why does it not remember what I just asked"
   * has one answer and this is it.
   */
  ONE_OFF {
    /**
     * Interactive, because somebody typing {@code /mini} is plainly waiting for the answer.
     *
     * <p>Nothing follows from it that a run with no tools would not want. The question handler a
     * surface registers decides whether the ask is <i>composed</i>, and {@link #tools()} being
     * false means nothing is, so the model is still offered no way to ask. What it does buy is the
     * other half: a chat surface that could not put its card on screen abandons the run instead of
     * carrying on writing an answer into nowhere.
     */
    @Override
    public boolean interactive() {
      return true;
    }

    @Override
    public Set<String> memoNames() {
      return Set.of("one-off", "one_off", "mini");
    }

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

    /**
     * And no tool search either, which is not a second way of saying the same thing.
     *
     * <p>{@link #tools()} empties the composition, and the tool-search advisor does not read it: it
     * puts {@code toolSearchTool} into the options itself and appends its own paragraph to the
     * system message. So a run documented as having nothing in between was reaching the model with
     * exactly one tool, and an explanation of how to look for more.
     */
    @Override
    public boolean toolSearch() {
      return false;
    }
  },

  /**
   * A turn answered out of what this deployment has been told to remember, and out of nothing else.
   *
   * <p>A person asks for it by memo — {@code /kb what do we do about a failing canary} — and what
   * they are asking for is not "answer this with the knowledge base as well", which an ordinary
   * chat run already does. It is the narrower thing: an answer whose sources they can name. A chat
   * run reaching for a web search, a shell or an MCP server produces something better in the
   * general case and unusable in this one, because nothing afterwards says which part came from
   * where.
   *
   * <p>So {@link #offers} is an allow-list rather than the usual few exclusions, and it is the only
   * one in this enum. That works because {@code offers} is asked about everything a run is composed
   * of and not the {@code @AgentTool} beans alone — without that this would name two tools and
   * quietly also receive a file-system sandbox, the todo tool and every MCP server the asker has
   * registered.
   *
   * <p>Conversation memory and automatic retrieval both stay on. This is a turn in a real
   * conversation, said into the same thread as everything around it, and retrieval is the whole
   * point of the run rather than an augmentation of it.
   *
   * <p>The memory tools are in deliberately, and are the one thing here that is not the knowledge
   * base. The two are separate stores answering the same question — what has this agent been told —
   * and a person who says {@code /kb} means both; leaving memory out would have the agent answer "I
   * have nothing on that" about something written in the file it keeps for exactly this.
   *
   * <p>So are the vision tools, for a narrower reason: a question can arrive as a picture. A person
   * who sends a screenshot and says {@code /kb} has asked about what is in it, and a run that
   * cannot look has nothing to search the knowledge base for. That is reading the question rather
   * than reaching past it, which is what everything else here is kept out for. Absent unless a
   * provider published a vision client, like every other tool {@code ModelToolsConfiguration}
   * registers.
   */
  KNOWLEDGE_BASE {
    @Override
    public boolean interactive() {
      return true;
    }

    @Override
    public Set<String> memoNames() {
      // Three spellings of one word, because a person typing at a chat is not consulting a manual.
      // Matching is case-insensitive, so /KB and /Knowledge-Base arrive here too.
      return Set.of("kb", "knowledge-base", "knowledge_base");
    }

    /**
     * No tool search, because there is nothing here to search. The allow-list below is four tools
     * on the best day, and the search would hand the model one tool and a paragraph about finding
     * the others — a round trip spent discovering what would have fitted in the prompt.
     */
    @Override
    public boolean toolSearch() {
      return false;
    }

    @Override
    public boolean offers(final Object tool) {
      // KnowledgeAdminTools is here because it is a knowledge-base tool; who actually receives it
      // is not this method's ruling but @AgentTool(admin = true) and app.ai.admins, which are
      // applied either way.
      return tool instanceof KnowledgeBaseTools
          || tool instanceof KnowledgeAdminTools
          || tool instanceof MemoryTools
          || tool instanceof VisionTools;
    }
  }
}
