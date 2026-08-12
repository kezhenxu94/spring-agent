package me.kezhenxu94.springagent.core.config;

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
      String systemPrompt,
      String scheduledTaskPrompt) {

    public static final String DEFAULT_SCHEDULED_TASK_PROMPT =
        """
        A scheduled task of yours has fired. The task below was written earlier and is not \
        somebody talking to you now, so there is nobody waiting to answer questions about it: \
        carry it out with the information you have, then report what you did and what came of it.

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
        chatMemory = new ChatMemory(0, null);
      }
      if (vectorstore == null) {
        vectorstore = new VectorStore(null);
      }
    }

    public record BotInterceptor(int guideThreshold) {}

    /**
     * How much conversation history is replayed to the model, and where it is kept when {@code
     * app.persistence.type} selects a relational database.
     *
     * @param maxMessages size of the message window replayed on each turn
     * @param jdbc settings for the JDBC backend, ignored when MongoDB is selected
     */
    public record ChatMemory(int maxMessages, Jdbc jdbc) {
      public ChatMemory {
        if (maxMessages <= 0) {
          maxMessages = 100;
        }
        if (jdbc == null) {
          jdbc = new Jdbc(null, null, null, null);
        }
      }

      /**
       * Any database Spring AI has a chat memory dialect for; the dialect and the schema script are
       * derived from the URL. The default is a local SQLite file, which needs no server. WAL plus a
       * busy timeout are part of that default because concurrent agent streams write to the same
       * file and SQLite serialises writers: without them a second writer fails immediately with
       * SQLITE_BUSY.
       *
       * @param initializeSchema whether to run Spring AI's schema script on startup; leave it on
       *     unless the table is managed elsewhere
       */
      public record Jdbc(String url, String username, String password, Boolean initializeSchema) {
        public Jdbc {
          if (url == null || url.isBlank()) {
            url = "jdbc:sqlite:data/chat-memory.db?journal_mode=WAL&busy_timeout=5000";
          }
          if (initializeSchema == null) {
            initializeSchema = true;
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
