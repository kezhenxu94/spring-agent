package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Selecting jpa swaps the whole conversation store, here into the SQLite file {@code
 * spring.datasource} names — the same database the domain repositories use.
 *
 * <p>The round trip is what proves the schema was created: Spring AI gates its schema initializer
 * on Boot's embedded-database check, which SQLite fails, so this passes only because {@code
 * application.yaml} asks for {@code initialize-schema: always}.
 */
@SpringBootTest(properties = "app.persistence.type=jpa")
class ChatMemoryJpaTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired ChatMemoryRepository chatMemoryRepository;
  @Autowired DataSource dataSource;

  @Test
  @DisplayName("the only chat memory repository is the JDBC one, and it round trips messages")
  void jpaBacksTheChatMemory() {
    assertThat(context.getBeansOfType(ChatMemoryRepository.class)).hasSize(1);
    assertThat(chatMemoryRepository).isInstanceOf(JdbcChatMemoryRepository.class);

    chatMemoryRepository.saveAll(
        "conversation-1", List.of(new UserMessage("ping"), new AssistantMessage("pong")));

    assertThat(chatMemoryRepository.findByConversationId("conversation-1"))
        .extracting(Message::getText)
        .containsExactly("ping", "pong");
  }

  @Test
  @DisplayName("conversation history shares the spring.datasource database with the domain tables")
  void historyLivesInTheDomainDatabase() {
    // Read from the application's own DataSource rather than from the repository, which is the
    // whole point: there is one database, and the chat memory table is in it. A repository built
    // against a second DataSource would pass the round trip above and fail here.
    final var tables =
        new JdbcTemplate(dataSource)
            .queryForList("select name from sqlite_master where type = 'table'", String.class);

    assertThat(tables).contains("SPRING_AI_CHAT_MEMORY").contains(ScheduledTask.COLLECTION_NAME);
  }
}
