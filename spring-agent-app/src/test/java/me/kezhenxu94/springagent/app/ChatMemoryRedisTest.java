package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
}
