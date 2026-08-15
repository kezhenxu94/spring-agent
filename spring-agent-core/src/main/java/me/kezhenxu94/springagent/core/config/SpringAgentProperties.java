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
   */
  public record Ai(
      BotInterceptor botInterceptor,
      Set<String> admins,
      Map<String, ModelPricing> modelPricing,
      VectorStore vectorstore,
      Tools tools,
      String systemPrompt,
      String scheduledTaskPrompt) {

    /**
     * What the agent is told when an application states no prompt of its own. Written to suit any
     * surface: it names no chat, no terminal and no tool that is not part of core, so an
     * integration overrides it to add its own rules rather than to restate these.
     *
     * <p>Rendered against the same variables as any other prompt — {@code userId}, {@code chatId}
     * and {@code chatType} are always supplied, the rest default to empty.
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
        Never invent details.\
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

    public Ai {
      if (systemPrompt == null || systemPrompt.isBlank()) {
        systemPrompt = DEFAULT_SYSTEM_PROMPT;
      }
      if (scheduledTaskPrompt == null || scheduledTaskPrompt.isBlank()) {
        scheduledTaskPrompt = DEFAULT_SCHEDULED_TASK_PROMPT;
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
        tools = new Tools(null);
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
     * between auto-configurations and so has to be readable before any bean exists.
     */
    public record Tools(AskUserQuestion askUserQuestion) {
      public Tools {
        if (askUserQuestion == null) {
          askUserQuestion = new AskUserQuestion(true, null);
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
     * @param file where {@code VectorStoreConfiguration} mirrors the in-memory index; ignored by
     *     the other backends, which keep their own storage
     */
    public record VectorStore(String file) {
      public VectorStore {
        if (file == null || file.isBlank()) {
          file = "data/vectorstore.json";
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
