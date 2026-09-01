package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoChatMemoryRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
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
    // This module's own rather than Spring AI's, which PersistenceAutoConfigurationFilter keeps out
    // — see MongoChatMemoryRepo for why. `hasSize(1)` is the load-bearing half: upstream's bean
    // backs off in front of nothing, so if the filter ever stopped dropping it the context would
    // hold two repositories and whichever won would be a matter of luck.
    assertThat(context.getBeansOfType(ChatMemoryRepository.class))
        .hasSize(1)
        .allSatisfy(
            (name, repository) -> assertThat(repository).isInstanceOf(MongoChatMemoryRepo.class));
  }
}
