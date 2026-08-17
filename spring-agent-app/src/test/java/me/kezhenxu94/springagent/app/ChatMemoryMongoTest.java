package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.mongo.MongoChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Selecting mongodb swaps the whole conversation store, leaving the JPA one out of the context. */
@SpringBootTest(properties = "app.persistence.type=mongodb")
class ChatMemoryMongoTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("exactly one chat memory repository is registered, backed by MongoDB")
  void mongoBacksTheChatMemory() {
    assertThat(context.getBeansOfType(ChatMemoryRepository.class))
        .hasSize(1)
        .allSatisfy(
            (name, repository) ->
                assertThat(repository).isInstanceOf(MongoChatMemoryRepository.class));
  }
}
