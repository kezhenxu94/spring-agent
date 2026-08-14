package me.kezhenxu94.springagent.core.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record SpringAgentProperties(Dashscope dashscope, Ai ai) {

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
      ChatMemory chatMemory,
      VectorStore vectorstore,
      Tools tools,
      String systemPrompt,
      String scheduledTaskPrompt) {

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
        throw new IllegalArgumentException("app.ai.system-prompt must not be blank");
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
      if (chatMemory == null) {
        chatMemory = new ChatMemory(0);
      }
      if (vectorstore == null) {
        vectorstore = new VectorStore(null);
      }
      if (tools == null) {
        tools = new Tools(null);
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

    public record BotInterceptor(int guideThreshold) {}

    /**
     * How much conversation history is replayed to the model.
     *
     * <p>Where it is kept is not configured here at all. Every backend wires Spring AI's own chat
     * memory repository, which reads its own {@code spring.ai.chat.memory.repository.*} properties
     * and shares the backend's connection — for JDBC that means {@code spring.datasource}, and
     * conversation history lands beside the domain tables.
     *
     * @param maxMessages size of the message window replayed on each turn
     */
    public record ChatMemory(int maxMessages) {
      public ChatMemory {
        if (maxMessages <= 0) {
          maxMessages = 100;
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
    public record Image(String apiKey, String baseUrl, String model) {}

    public record Vision(String apiKey, String baseUrl, String model) {}
  }
}
