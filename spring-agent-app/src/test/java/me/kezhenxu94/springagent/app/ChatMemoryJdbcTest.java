package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Selecting JDBC swaps the whole conversation store, here for the default local SQLite file. */
@SpringBootTest(properties = "app.ai.chat-memory.type=jdbc")
class ChatMemoryJdbcTest extends AbstractIntegrationTest {

  static final Path databaseFile = tempDatabaseFile();

  @Autowired ApplicationContext context;
  @Autowired ChatMemoryRepository chatMemoryRepository;

  @DynamicPropertySource
  static void jdbcProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "app.ai.chat-memory.jdbc.url",
        () -> "jdbc:sqlite:" + databaseFile + "?journal_mode=WAL&busy_timeout=5000");
  }

  @Test
  @DisplayName("the only chat memory repository is the JDBC one, and it round trips messages")
  void jdbcBacksTheChatMemory() {
    assertThat(context.getBeansOfType(ChatMemoryRepository.class)).hasSize(1);
    assertThat(chatMemoryRepository).isInstanceOf(JdbcChatMemoryRepository.class);

    chatMemoryRepository.saveAll(
        "conversation-1", List.of(new UserMessage("ping"), new AssistantMessage("pong")));

    assertThat(chatMemoryRepository.findByConversationId("conversation-1"))
        .extracting(Message::getText)
        .containsExactly("ping", "pong");
    assertThat(databaseFile).exists();
  }

  private static Path tempDatabaseFile() {
    try {
      // A directory that does not exist yet: the configuration is expected to create it.
      return Files.createTempDirectory("chat-memory").resolve("nested").resolve("chat-memory.db");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
