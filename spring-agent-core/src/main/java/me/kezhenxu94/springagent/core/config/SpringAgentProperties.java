package me.kezhenxu94.springagent.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param locale which language the agent's own text speaks — what core writes into a conversation
 *     rather than what the model writes. Defaults to the host's, so setting it is for a workspace
 *     whose language differs from the machine the agent runs on. Each surface states its own for
 *     what it says directly; see {@link CoreMessages} for what this one selects.
 *     <p>Only which language, and not how the bundles behave: encoding, caching and the fallback
 *     for a locale that ships no bundle are the application's {@code spring.messages} settings,
 *     since {@link CoreMessages} reads through the application's own message source.
 */
@ConfigurationProperties(prefix = "app")
public record SpringAgentProperties(Dashscope dashscope, Ai ai, Locale locale) {

  public SpringAgentProperties {
    // An application that configures no DashScope at all is a legitimate one — spring-agent-cli is
    // the first — and leaving this null made the vision ChatClient fail the whole context with a
    // NullPointerException at startup rather than the image tools simply not working.
    dashscope = dashscope == null ? Dashscope.NONE : dashscope;
  }

  /**
   * @param scheduledTaskPrompt what a firing scheduled task says to the model, as a template over
   *     {@code {taskText}} — the prompt the task was created with. Defaults to {@link
   *     #DEFAULT_SCHEDULED_TASK_PROMPT}, since a deployment that never schedules anything has no
   *     reason to state one.
   * @param subagentPrompt how a subagent is introduced to itself, as a template over {@code
   *     {taskText}} — the brief the run that started it wrote. Defaults to {@link
   *     #DEFAULT_SUBAGENT_PROMPT}, on the same reasoning.
   */
  public record Ai(
      BotInterceptor botInterceptor,
      Set<String> admins,
      Map<String, ModelPricing> modelPricing,
      VectorStore vectorstore,
      Tools tools,
      String systemPrompt,
      String scheduledTaskPrompt,
      String subagentPrompt) {

    /**
     * What the agent is told when an application states no prompt of its own. Written to suit any
     * surface: it names no chat, no terminal and no tool that is not part of core, so an
     * integration overrides it to add its own rules rather than to restate these.
     *
     * <p>Rendered against the same variables as any other prompt — {@code userId}, {@code chatId}
     * and {@code chatType} are always supplied, the rest default to empty. {@code replyFormat} is
     * the surface's own: whichever integration receives the answer says there how it wants one
     * written, and a surface with nothing to say leaves the slot empty.
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
        """
        You are a helpful AI assistant working alongside people. You answer questions, look \
        things up, and carry out multi-step tasks on their behalf using the tools available to \
        you.

        # Current conversation
        - Sender user ID: {userId}
        - Conversation: {chatId}
        - Conversation type: {chatType}

        # Where your files live
        {homeDirs}
        Read and write these with the filesystem and shell tools; a path outside them is out of \
        bounds. Skills in any of them are already loaded and listed by ListSkills. Your memory \
        tools only reach your own memories/, so read a shared MEMORY.md as an ordinary file. Put \
        a file in a shared home when it is meant for the people who share it, and in your own \
        when it is not.

        # Working rules
        - Before replying, call MemoryView("MEMORY.md") to read what you already know about this \
        user, and keep it in mind.
        - For anything that needs several steps, several tool calls, or noticeable time, call \
        TodoWrite first to break the work down, then update each item as you go so the user can \
        watch progress. Skip TodoWrite for simple one-shot answers.
        - The last TodoWrite call comes before your final answer: no item may be left in_progress \
        when you stop.
        - Call CurrentDateTime whenever the answer depends on the current date or time, including \
        relative expressions like "today", "this week" or "in two hours". Never guess the current \
        time or the user's timezone.

        # Handing work to a subagent
        StartSubagent runs another you on one task, with a context window of its own, and gives \
        you back only what it reports. Reach for it when the work is large but its middle is not \
        worth your attention:
        - Reading something long to answer a narrow question about it — a transcript, a log, a \
        file you would otherwise page through here.
        - The same question in several places: one subagent per repository, cluster or service, \
        all started before you wait for any of them.
        - A search whose path you cannot predict, and whose dead ends you have no reason to keep.

        Do the work yourself when it is one or two tool calls, when it only makes sense against \
        this conversation, or when it needs the user: a subagent sees neither and cannot ask.

        The brief is the whole of what a subagent gets, so state the task, every fact it needs, \
        and what to report back. Collect each answer with WaitForSubagent before you finish your \
        turn, and call CancelSubagent on any you no longer need — one you walk away from goes on \
        running, and goes on costing.

        # Ask before you do something you cannot undo
        Get on with the work. The tools you have are there to be used, and asking to use them \
        normally is friction, not care. Stop and ask only when you are about to:
        - Destroy or overwrite something that already exists — deleting or truncating files, \
        replacing a document's contents, dropping data, or any shell command whose damage you \
        could not reverse.
        - Reach someone outside this conversation, since a message cannot be unsent.
        - Change a live production system. This one you must always ask about, however small or \
        reversible the change looks: writes through an MCP server that reaches production, \
        anything applied to a Kubernetes cluster or its workloads, deploys, restarts, scaling and \
        config changes, and anything else touching real traffic or real data. Inspecting \
        production — reading, listing, describing, querying — is fine and needs no permission.

        Your Bash tool may not be running in a sandbox at all: it may be the user's own machine, \
        with their files, their credentials and their network. Treat an irreversible shell \
        command as you would any other irreversible action.

        Everything else — reading, searching, writing new files, publishing, editing docs and \
        sheets, scheduling — go ahead and do, then say what you did.

        When you do ask, call AskUserQuestionTool with the safest option first and say plainly \
        what would be lost. If the user has already approved this exact action, or there is \
        nobody to ask, do the reversible part and report what you stopped short of.

        # Style
        - Reply in the language the user wrote in.
        - Be concise, warm and direct. Skip filler and ceremony.
        - When you are unsure of a fact, say so and suggest where the user might confirm it. \
        Never invent details.

        {replyFormat}\
        """;

    public static final String DEFAULT_SCHEDULED_TASK_PROMPT =
        """
        A scheduled task of yours has fired. The task below was written earlier and is not \
        somebody talking to you now, so there is nobody waiting to answer questions about it: \
        carry it out with the information you have, then report what you did and what came of it.

        Because nobody is there to ask, you cannot get permission for anything the task did not \
        already authorise. Do the reversible part, stop before anything destructive or \
        irreversible that the task does not plainly call for, and say in your report what you \
        stopped short of.

        Do not create, reschedule or cancel a scheduled task as part of carrying this one out — \
        it is already scheduled, and scheduling it again would only duplicate it.

        # The task
        {taskText}\
        """;

    /**
     * What a subagent is told about being one, ahead of the brief it was given. Everything a
     * subagent cannot do — see the conversation, ask the user, start a subagent of its own — it has
     * to be told here, because the tools that would let it are simply absent and a model that is
     * not told reaches for them anyway.
     */
    public static final String DEFAULT_SUBAGENT_PROMPT =
        """
        You are running as a subagent. Another run of you needed work done that would not fit in \
        its own context, wrote the brief below, and is waiting for what you report back.

        What that means for you:
        - You cannot see that conversation. The brief is everything you have been told; nothing \
        else is coming. Where it leaves something open, decide, act, and say in your report what \
        you decided and why.
        - There is nobody to ask. Do the reversible part, stop before anything destructive or \
        irreversible that the brief does not plainly call for, and say what you stopped short of.
        - Your final message is the whole of what your caller reads. Everything they need has to \
        be in it: what you found, the numbers and names and paths themselves rather than a \
        reference to where you saw them, and what you could not settle. Nothing else you did \
        survives.
        - Write it for another agent to act on, not for a person to read: no greeting, no closing \
        offer of further help, no formatting for a chat window.
        - You share a workspace with your caller, so a file you write is a file they can read. Say \
        the path of anything you leave behind.

        # The brief
        {taskText}\
        """;

    public Ai {
      if (systemPrompt == null || systemPrompt.isBlank()) {
        systemPrompt = DEFAULT_SYSTEM_PROMPT;
      }
      if (scheduledTaskPrompt == null || scheduledTaskPrompt.isBlank()) {
        scheduledTaskPrompt = DEFAULT_SCHEDULED_TASK_PROMPT;
      }
      if (subagentPrompt == null || subagentPrompt.isBlank()) {
        subagentPrompt = DEFAULT_SUBAGENT_PROMPT;
      }
      if (admins == null) {
        admins = Set.of();
      }
      if (modelPricing == null) {
        modelPricing = Map.of();
      }
      if (vectorstore == null) {
        vectorstore = new VectorStore(null);
      }
      if (tools == null) {
        tools = new Tools(null, null);
      }
      // The one field this block used to leave null, which cost every application that did not
      // configure it a NullPointerException from LargeResponseInterceptor — not at startup, but on
      // the first tool call of the first turn.
      if (botInterceptor == null) {
        botInterceptor = new BotInterceptor(BotInterceptor.DEFAULT_GUIDE_THRESHOLD);
      }
    }

    /**
     * Settings for individual tools, for the ones a deployment has a reason to turn off or tune.
     *
     * <p>Not every tool is configured here: which shell backend is in play is chosen by {@code
     * app.ai.tools.shell.type}, read directly by {@code ShellBackendResolver} because it selects
     * between auto-configurations and so has to be readable before any bean exists, and {@code
     * app.ai.tools.publish-file.base-url} is a {@code @Value} on {@code PublishFileTool} so that a
     * deployment which never states it fails at startup rather than on the first published link.
     */
    public record Tools(AskUserQuestion askUserQuestion, Subagent subagent) {
      public Tools {
        if (askUserQuestion == null) {
          askUserQuestion = new AskUserQuestion(true, null);
        }
        if (subagent == null) {
          subagent = new Subagent(0, null, null);
        }
      }

      /**
       * @param maxConcurrent how many subagents one run may have going at once. Bounded because
       *     each one is a run in full — an MCP handshake per server it can reach, its own workspace
       *     and skills build, its own tool index lookup — so a model that fans out as far as it
       *     likes can spend a great deal before it says anything. Over the limit the tool refuses
       *     and tells the model to collect an answer first. Zero and below are read as "not
       *     configured" and replaced by {@link #DEFAULT_MAX_CONCURRENT}.
       * @param waitPoll how long {@code WaitForSubagent} blocks before handing the turn back to the
       *     model to ask again. It exists because that wait happens on a Reactor {@code
       *     boundedElastic} worker, and so does every subagent's own stream: the pool is a fixed
       *     number of single-threaded executors, a worker is pinned to one of them for its whole
       *     life, and past capacity a new worker is handed one that is already busy. A wait that
       *     never let go could therefore be sitting on the very thread the subagent it waits for
       *     needs, and neither would ever move again. Letting go on a timer is what makes that
       *     impossible rather than unlikely. Longer costs nothing while a subagent is quick — the
       *     wait returns the moment it finishes — and only a further model call once it is not, so
       *     this trades tokens against how long a stalled thread is held.
       * @param waitTimeout the ceiling on waiting for a subagent, in two places: how long one
       *     subagent may be waited for by {@code WaitForSubagent}, across however many polls, and
       *     how long a run that has finished talking may be held open for the subagents it never
       *     collected. Reached means something is wrong rather than slow, so the subagent is
       *     cancelled and — where there is still a model listening — it is told the work did not
       *     happen. A bound that is never hit is still what keeps a bug in this area from becoming
       *     a turn that hangs for good with nothing in the log.
       */
      public record Subagent(int maxConcurrent, Duration waitPoll, Duration waitTimeout) {

        public static final int DEFAULT_MAX_CONCURRENT = 3;

        /**
         * Long enough that a subagent worth starting usually finishes inside the first one, so the
         * poll costs nothing in the ordinary case.
         */
        public static final Duration DEFAULT_WAIT_POLL = Duration.ofSeconds(60);

        /** The chat timeout, since a subagent is a chat turn and cannot sensibly outlast one. */
        public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(30);

        public Subagent {
          if (maxConcurrent <= 0) {
            maxConcurrent = DEFAULT_MAX_CONCURRENT;
          }
          if (waitPoll == null || waitPoll.isZero() || waitPoll.isNegative()) {
            waitPoll = DEFAULT_WAIT_POLL;
          }
          if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
            waitTimeout = DEFAULT_WAIT_TIMEOUT;
          }
        }
      }

      /**
       * @param enabled whether the agent is offered the tool at all. It is still only offered on a
       *     run whose integration registered somewhere to put the questions.
       * @param ttl how long the questions stay answerable. The agent does not wait for an answer,
       *     so this is the only thing bounding how late one may arrive. Feishu caps it from above
       *     regardless: a card entity expires 14 days after it is created, and the form lives on
       *     that card.
       */
      public record AskUserQuestion(boolean enabled, Duration ttl) {
        public AskUserQuestion {
          if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofHours(24);
          }
        }
      }
    }

    /**
     * @param guideThreshold how long a tool result may be before {@code LargeResponseInterceptor}
     *     writes it to the user's workspace and hands the model a pointer instead. Zero and below
     *     are read as "not configured" and replaced by {@link #DEFAULT_GUIDE_THRESHOLD}, since a
     *     threshold of zero would divert every result a tool ever returned.
     */
    public record BotInterceptor(int guideThreshold) {

      public static final int DEFAULT_GUIDE_THRESHOLD = 30000;

      public BotInterceptor {
        if (guideThreshold <= 0) {
          guideThreshold = DEFAULT_GUIDE_THRESHOLD;
        }
      }
    }

    /**
     * Settings for the vector store backing the tool search index.
     *
     * <p>Which store that is comes from Spring AI's own {@code spring.ai.vectorstore.type}, not
     * from here: its vector store auto-configurations already condition on that property, so a
     * second selector of ours would only have to be kept in sync with it. What is left is the one
     * setting Spring AI has no property for, because it has no auto-configuration for the simple
     * store.
     *
     * @param simple settings for the {@code simple} store only, nested under its own name so that a
     *     backend added here later cannot be mistaken for it
     */
    public record VectorStore(Simple simple) {
      public VectorStore {
        if (simple == null) {
          simple = new Simple(null);
        }
      }

      /**
       * @param file where {@code VectorStoreConfiguration} mirrors the in-memory index; read only
       *     when {@code spring.ai.vectorstore.type} is {@code simple}, the other backends keeping
       *     their own storage
       */
      public record Simple(String file) {
        public Simple {
          if (file == null || file.isBlank()) {
            file = "data/vectorstore.json";
          }
        }
      }
    }

    /** Per-model token pricing used to estimate the approximate cost of a chat completion. */
    public record ModelPricing(
        double nonThinkingInputPerMillion,
        double thinkingInputPerMillion,
        double outputPerMillion,
        Currency currency) {

      @Getter
      @Accessors(fluent = true)
      @RequiredArgsConstructor
      public enum Currency {
        CNY("¥"),
        USD("$");

        private final String symbol;
      }
    }
  }

  public record Dashscope(Image image, Vision vision) {

    /**
     * What an application that configures no DashScope gets. The clients are still built — the
     * image and vision tools are unconditional beans — but with nothing to call, so a call fails as
     * a tool error the agent can report rather than taking the context down at startup.
     */
    public static final Dashscope NONE =
        new Dashscope(new Image(null, null, null), new Vision(null, null, null));

    public Dashscope {
      image = image == null ? new Image(null, null, null) : image;
      vision = vision == null ? new Vision(null, null, null) : vision;
    }

    public record Image(String apiKey, String baseUrl, String model) {}

    public record Vision(String apiKey, String baseUrl, String model) {}
  }
}
