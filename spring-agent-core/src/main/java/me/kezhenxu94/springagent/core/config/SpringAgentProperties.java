package me.kezhenxu94.springagent.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import me.kezhenxu94.springagent.core.advisors.AutoSkillToolsAdvisor;
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
public record SpringAgentProperties(
    Dashscope dashscope, Ai ai, Locale locale, Shutdown shutdown, Scheduling scheduling) {

  public SpringAgentProperties {
    // An application that configures no DashScope at all is a legitimate one — spring-agent-app-cli
    // is
    // the first — and leaving this null made the vision ChatClient fail the whole context with a
    // NullPointerException at startup rather than the image tools simply not working.
    dashscope = dashscope == null ? Dashscope.NONE : dashscope;
    shutdown = shutdown == null ? new Shutdown(null) : shutdown;
    scheduling = scheduling == null ? new Scheduling(null, null) : scheduling;
  }

  /**
   * @param inFlightWaitTimeout how long {@code SpringAgent#onShutdown} waits for in-flight agent
   *     streams to finish before giving up on them. Must agree with the deployment's own grace
   *     period — Kubernetes {@code terminationGracePeriodSeconds} — or the pod is killed while this
   *     wait is still running. Defaults to {@link #DEFAULT_IN_FLIGHT_WAIT_TIMEOUT}.
   */
  public record Shutdown(Duration inFlightWaitTimeout) {
    public static final Duration DEFAULT_IN_FLIGHT_WAIT_TIMEOUT = Duration.ofMinutes(30);

    public Shutdown {
      if (inFlightWaitTimeout == null
          || inFlightWaitTimeout.isZero()
          || inFlightWaitTimeout.isNegative()) {
        inFlightWaitTimeout = DEFAULT_IN_FLIGHT_WAIT_TIMEOUT;
      }
    }
  }

  /**
   * How the timers behind scheduled tasks and situation triage are run. Under {@code app} rather
   * than {@code app.ai}: nothing here is a model setting.
   *
   * @param sweepInterval how often {@code ScheduledTaskSweeper} looks for tasks that have come due,
   *     and so the worst case by which a task fires late. Defaults to {@link
   *     #DEFAULT_SWEEP_INTERVAL}.
   *     <p>Thirty seconds rather than the five {@code app.events.sweep-interval} uses, because the
   *     tightest schedule anybody can create is five minutes — {@code
   *     ScheduledTaskTool#enforceMinimumInterval} raises anything shorter — so this is at worst a
   *     tenth of the shortest cadence in play, and the tasks people actually write are hourly and
   *     daily. Firing was never to the second in any case: {@code SpringAgent#fire} hands the run
   *     to a Reactor scheduler and the first token is seconds away. Against that, a sweep reads the
   *     whole active set, which on the Redis backend is a set read plus a hash read per member.
   *     Lower this only alongside that five-minute floor; the two are what make each other
   *     reasonable.
   * @param poolSize how many threads the shared {@code taskScheduler} has. Defaults to {@link
   *     #DEFAULT_POOL_SIZE}.
   *     <p>It is shared, and by more than it looks: the scheduled-task sweeper, {@code
   *     SituationSweeper}, every {@code @Scheduled} method in the application, and whatever an SDK
   *     consumer adds. Neither sweep holds its thread for the length of an agent run — {@code
   *     SpringAgent#fire} returns immediately — so four is ample for the sweeps themselves; the
   *     knob is here for a deployment that adds blocking scheduled work of its own, which would
   *     otherwise starve them.
   *     <p>Note that {@code spring.task.scheduling.pool.size} does <em>not</em> reach this. The
   *     bean is built by hand in {@code SpringAgentCoreAutoConfiguration} and named {@code
   *     taskScheduler}, which displaces Boot's own, so Boot's settings for it are read by nothing.
   */
  public record Scheduling(Duration sweepInterval, Integer poolSize) {
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(30);
    public static final int DEFAULT_POOL_SIZE = 4;

    public Scheduling {
      if (sweepInterval == null || sweepInterval.isZero() || sweepInterval.isNegative()) {
        sweepInterval = DEFAULT_SWEEP_INTERVAL;
      }
      if (poolSize == null || poolSize < 1) {
        poolSize = DEFAULT_POOL_SIZE;
      }
    }
  }

  /**
   * @param admins the user ids this deployment trusts with everybody else's work, by whatever id
   *     the surface puts on a request — a Feishu open id, a CLI user name. Empty by default, and an
   *     empty set is the feature being off. See {@link Admins} for what membership grants and why
   *     it is not a UI role: an admin causes things to happen as somebody else, in runs that keep
   *     the identity they started with.
   * @param scheduledTaskPrompt what a firing scheduled task says to the model, as a template over
   *     {@code {taskText}} — the prompt the task was created with. Defaults to {@code
   *     core/prompts/scheduled-task-prompt.md} in the host's language, since a deployment that
   *     never schedules anything has no reason to state one.
   * @param subagentPrompt how a subagent is introduced to itself, as a template over {@code
   *     {taskText}} — the brief the run that started it wrote. Defaults to {@code
   *     core/prompts/subagent-prompt.md}, on the same reasoning.
   */
  public record Ai(
      Set<String> admins,
      Map<String, ModelPricing> modelPricing,
      VectorStore vectorstore,
      Rag rag,
      Tools tools,
      String systemPrompt,
      String scheduledTaskPrompt,
      String subagentPrompt) {

    /**
     * What the agent is told when an application states no prompt of its own, in whatever language
     * the host speaks.
     *
     * <p>Read from {@code core/prompts/}, the same files {@link PromptDefaults} supplies to a Boot
     * application, rather than held here as constants. Two reasons, and the second is the one that
     * bites:
     *
     * <ul>
     *   <li>a prompt in two places drifts, and the copy nobody edits is the one a deployment ends
     *       up running;
     *   <li>a constant has one language. This is the fallback for a consumer that builds these
     *       properties itself — the SDK's path, where no {@code EnvironmentPostProcessor} runs —
     *       and an English constant there is six thousand characters of English at the head of
     *       every request, which is enough on its own to have the model reason in English however
     *       Chinese the conversation is.
     * </ul>
     *
     * <p>The host's language rather than {@code app.locale}: a record's compact constructor cannot
     * see its parent's components. A Boot application never reaches this — {@link PromptDefaults}
     * has already supplied the prompt in the configured language, and a stated property wins — so
     * this is the answer for the one caller that has no such setting to read.
     */
    static String defaultPrompt(final String name) {
      return LocalizedPrompt.text(name, null);
    }

    public Ai {
      if (systemPrompt == null || systemPrompt.isBlank()) {
        systemPrompt = defaultPrompt(PromptDefaults.SYSTEM_PROMPT);
      }
      if (scheduledTaskPrompt == null || scheduledTaskPrompt.isBlank()) {
        scheduledTaskPrompt = defaultPrompt(PromptDefaults.SCHEDULED_TASK_PROMPT);
      }
      if (subagentPrompt == null || subagentPrompt.isBlank()) {
        subagentPrompt = defaultPrompt(PromptDefaults.SUBAGENT_PROMPT);
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
      if (rag == null) {
        rag = new Rag(false, 0, 0d, 0, 0);
      }
      if (tools == null) {
        tools = new Tools(null, null, null, null, null);
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
     *
     * <p>The values these fall back to are supplied as properties by {@link ToolDefaults}, so an
     * application that configures nothing is bound the same as one in this repository. The
     * fallbacks in the compact constructors below remain for a record built directly, and for a
     * property explicitly set to nothing meaningful.
     */
    public record Tools(
        AskUserQuestion askUserQuestion,
        Subagent subagent,
        Skills skills,
        Integer maxResultChars,
        Integer maxInlinedInputChars) {

      /**
       * How long any tool's result may be before {@code LargeResponseInterceptor} writes it to the
       * user's workspace and hands the model a pointer to the file instead.
       *
       * <p>Counted in {@code String.length()} — UTF-16 chars, which is what a result costs a
       * context window rather than what it costs a disk. The number is therefore not the same
       * quantity of text in every language: this many characters of English is roughly a quarter as
       * many tokens, while this many characters of Chinese is close to that many tokens. Set it for
       * the language the deployment's tools actually return.
       *
       * <p>Zero and below are read as "not configured" and replaced by this, since a threshold of
       * zero would divert every result a tool ever returned.
       */
      public static final int DEFAULT_MAX_RESULT_CHARS = 30_000;

      /**
       * How much of a file {@code ToolInputFileRefs} will read into a tool argument that was given
       * as an {@code @file:} reference.
       *
       * <p>Larger than {@link #DEFAULT_MAX_RESULT_CHARS} on purpose, and by a wide margin: the
       * whole point of a reference is that the content never has to fit in a context window, so the
       * only thing this has to bound is the memory one call may take and the size of the request
       * the tool then makes. Going over is an error rather than a truncation — a tool argument cut
       * in half is malformed JSON that a lenient parser may accept part of, which is a corrupted
       * document rather than a failed call.
       */
      public static final int DEFAULT_MAX_INLINED_INPUT_CHARS = 300_000;

      public Tools {
        if (askUserQuestion == null) {
          askUserQuestion = new AskUserQuestion(AskUserQuestion.DEFAULT_ENABLED, null);
        }
        if (subagent == null) {
          subagent = new Subagent(0, null, null);
        }
        if (skills == null) {
          skills = new Skills(null, 0);
        }
        if (maxResultChars == null || maxResultChars <= 0) {
          maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        }
        if (maxInlinedInputChars == null || maxInlinedInputChars <= 0) {
          maxInlinedInputChars = DEFAULT_MAX_INLINED_INPUT_CHARS;
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

        /**
         * Enough that fan-out work the model asks for in one go actually runs in one go. Bounded
         * rather than unbounded because each subagent is a run in full; see {@link #maxConcurrent}.
         */
        public static final int DEFAULT_MAX_CONCURRENT = 10;

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
       * @param offerAfterExpensiveRuns whether a turn that has cost a great many tool calls ends
       *     with the agent offering to keep what it worked out as a skill. Nothing is written by
       *     the offer itself — the user has to say yes, on the turn after — so this is a switch
       *     over whether the offer is ever made, for a deployment that would rather its skills were
       *     written by hand. Null is read as "not configured" and replaced by {@link
       *     #DEFAULT_OFFER_AFTER_EXPENSIVE_RUNS}.
       * @param toolCallThreshold how many tool calls one turn has to make first. Counted per turn
       *     rather than per conversation, because it is the cost of answering this question again
       *     that a skill would save. Zero and below are read as "not configured" and replaced by
       *     {@link #DEFAULT_TOOL_CALL_THRESHOLD}; turning the offer off is what {@code
       *     offerAfterExpensiveRuns} is for, since a threshold of zero would offer on every turn.
       */
      public record Skills(Boolean offerAfterExpensiveRuns, int toolCallThreshold) {

        /**
         * On, because it costs nothing until a turn is expensive and writes nothing without being
         * asked to. A user who never wants it says no, and a deployment that never wants it turns
         * this off.
         */
        public static final boolean DEFAULT_OFFER_AFTER_EXPENSIVE_RUNS = true;

        /**
         * See {@code AutoSkillToolsAdvisor.DEFAULT_TOOL_CALL_THRESHOLD}, which is where the number
         * is explained and which this hands over unchanged. Stated again here so that the property
         * has a default of its own if the advisor is ever swapped for another.
         */
        public static final int DEFAULT_TOOL_CALL_THRESHOLD =
            AutoSkillToolsAdvisor.DEFAULT_TOOL_CALL_THRESHOLD;

        public Skills {
          if (offerAfterExpensiveRuns == null) {
            offerAfterExpensiveRuns = DEFAULT_OFFER_AFTER_EXPENSIVE_RUNS;
          }
          if (toolCallThreshold <= 0) {
            toolCallThreshold = DEFAULT_TOOL_CALL_THRESHOLD;
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

        /**
         * Offered by default, since a run that has nowhere to put the questions is not offered the
         * tool anyway — a deployment that wants the model to guess rather than ask turns it off.
         */
        public static final boolean DEFAULT_ENABLED = true;

        /**
         * A day, which is about as long as a question is still worth answering: the run that asked
         * it is over, and the context the user would be answering into has moved on.
         */
        public static final Duration DEFAULT_TTL = Duration.ofHours(24);

        public AskUserQuestion {
          if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = DEFAULT_TTL;
          }
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

    /**
     * The knowledge base: what a user, group or tenant has told the agent to remember, retrieved
     * before the model answers.
     *
     * <p>Read only where a {@code KnowledgeBase} implementation is installed — that is, where a
     * {@code spring-agent-rag-*} module is on the classpath. Nothing here brings one into being,
     * and a deployment with no such module simply has no knowledge base: the tools are not
     * registered and no retrieval happens, whatever these say.
     *
     * <p>Where a knowledge base is chunked and stored is that module's business, not this record's.
     * What is here is the part core decides: whether to consult it, and how much it may contribute
     * to a turn.
     *
     * @param enabled whether this deployment has a knowledge base at all. <b>Off by default</b>,
     *     and deliberately: a knowledge base needs a vector database that the default deployment
     *     does not run, so defaulting it on would mean every application that merely has a {@code
     *     spring-agent-rag-*} module on its classpath refusing to start until one appeared. The
     *     same reasoning as {@code app.ai.tools.shell.type} defaulting to {@code none}.
     *     <p>This gates both the implementation bean and the automatic retrieval before each
     *     answer, so turning it on requires a reachable database and turning it off requires
     *     nothing. Individual runs opt out separately through {@code
     *     AgentScenario.knowledgeRetrieval()}.
     * @param topK how many chunks retrieval may put in front of the model. Every one of them is
     *     context the model pays for on the turn, so this trades recall against the prompt budget
     * @param similarityThreshold how close a chunk must be to be worth including, between 0 and 1.
     *     Too low and every turn drags in unrelated text that the model then has to reason around;
     *     too high and a knowledge base that is worded differently from the question never
     *     contributes at all
     * @param chunkSize the token target a document is split into. Chunks the retriever hands over
     *     whole, so this is also the granularity at which knowledge is either relevant or not
     * @param listPageSize how many documents a listing returns when the caller does not say
     */
    public record Rag(
        boolean enabled, int topK, double similarityThreshold, int chunkSize, int listPageSize) {

      public static final int DEFAULT_TOP_K = 4;

      /**
       * A raw cosine similarity, compared against the score the vector store returns.
       *
       * <p>This is model-dependent and worth measuring rather than trusting. Embeddings from a
       * modern model occupy a narrow cone, so two unrelated texts in the same language routinely
       * score 0.3 to 0.5: a threshold near the bottom of that band admits everything, and with a
       * knowledge base holding one document the effect is that every question retrieves it. That
       * was the earlier default here, and it made retrieval look broken rather than permissive.
       *
       * <p>There is no value that is right for every embedding model, so this is a starting point
       * and not a recommendation. {@code SearchKnowledge} is the instrument for replacing it: it
       * ignores this threshold on purpose and prints what each passage scored, so the number can be
       * set from what the model in use actually produces — ask it something the knowledge base
       * covers and something it does not, and put the threshold between the two.
       */
      public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.45d;

      public static final int DEFAULT_CHUNK_SIZE = 800;
      public static final int DEFAULT_LIST_PAGE_SIZE = 20;

      public Rag {
        // Zero means "unset" rather than "none": a record's primitives default to zero when the
        // property is absent, and a topK of zero would silently disable retrieval while the
        // enabled flag still claimed it was on.
        if (topK <= 0) {
          topK = DEFAULT_TOP_K;
        }
        if (similarityThreshold <= 0d || similarityThreshold > 1d) {
          similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
        }
        if (chunkSize <= 0) {
          chunkSize = DEFAULT_CHUNK_SIZE;
        }
        if (listPageSize <= 0) {
          listPageSize = DEFAULT_LIST_PAGE_SIZE;
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
