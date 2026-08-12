package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.mongo.MongoChatMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Chat memory stays on MongoDB unless app.ai.chat-memory.type says otherwise. */
@SpringBootTest
class ChatMemoryDefaultsTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("exactly one chat memory repository is registered, backed by MongoDB")
  void mongoIsTheDefault() {
    assertThat(context.getBeansOfType(ChatMemoryRepository.class))
        .hasSize(1)
        .allSatisfy(
            (name, repository) ->
                assertThat(repository).isInstanceOf(MongoChatMemoryRepository.class));
  }
}
