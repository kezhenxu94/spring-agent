package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Selecting redis swaps the whole conversation store, leaving the other two out of the context.
 *
 * <p>The type assertion earns its keep beyond symmetry with the other two backends. Spring AI's
 * Redis chat memory auto-configuration is {@code @ConditionalOnClass(RedisClient.class)}, a Jedis
 * class that only exists from 7.4.x, and spring-boot-dependencies manages Jedis below that. If the
 * version pin in {@code spring-agent-app/build.gradle} were lost, the condition would silently stop
 * matching and the context would fall back to the in-memory repository with nothing logged — this
 * assertion is what turns that into a failure.
 */
@SpringBootTest(properties = "app.persistence.type=redis")
class ChatMemoryRedisTest extends AbstractIntegrationTest {

  @Autowired ApplicationContext context;
  @Autowired ChatMemoryRepository chatMemoryRepository;

  @Test
  @DisplayName("the only chat memory repository is the Redis one, and it round trips messages")
  void redisBacksTheChatMemory() {
    assertThat(context.getBeansOfType(ChatMemoryRepository.class)).hasSize(1);
    assertThat(chatMemoryRepository).isInstanceOf(RedisChatMemoryRepository.class);

    chatMemoryRepository.saveAll(
        "conversation-redis", List.of(new UserMessage("ping"), new AssistantMessage("pong")));

    assertThat(chatMemoryRepository.findByConversationId("conversation-redis"))
        .extracting(Message::getText)
        .containsExactly("ping", "pong");
  }

  @Test
  @DisplayName("an assistant message carrying a model's own metadata comes back out")
  void assistantMetadataDoesNotCostTheMessage() {
    // The metadata a real chat model attaches, which is the point of this test: reads go through a
    // RediSearch index, and indexing a document is all-or-nothing. Under the repository's default
    // schema every metadata key is indexed as text through a $.metadata.* wildcard, and a value
    // that is not a string — `index` is a number here and `annotations` a list, both of which
    // OpenAI sends on every completion — makes RediSearch reject the whole document. It is still
    // stored, and JSON.GET still returns it, so nothing looks wrong until findByConversationId
    // quietly returns the user's turns and none of the assistant's.
    final var assistant =
        AssistantMessage.builder()
            .content("pong")
            .properties(
                Map.of(
                    "messageType", "ASSISTANT",
                    "role", "assistant",
                    "finishReason", "STOP",
                    "index", 0,
                    "annotations", List.of()))
            .build();

    chatMemoryRepository.saveAll(
        "conversation-redis-metadata", List.of(new UserMessage("ping"), assistant));

    assertThat(chatMemoryRepository.findByConversationId("conversation-redis-metadata"))
        .extracting(Message::getText)
        .containsExactly("ping", "pong");
  }
}
