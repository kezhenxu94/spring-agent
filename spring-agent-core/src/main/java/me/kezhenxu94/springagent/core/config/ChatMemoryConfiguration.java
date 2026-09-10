package me.kezhenxu94.springagent.core.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * How much of a conversation is replayed into the next turn.
 *
 * <p>The window is the one part of chat memory Spring AI's own auto-configuration does not let a
 * deployment state: {@code ChatMemoryAutoConfiguration} builds a {@link MessageWindowChatMemory}
 * with the builder's default of twenty messages and reads no property on the way. Everything else
 * under {@code spring.ai.chat.memory.*} configures where the messages are stored, which is why the
 * knob for this one is {@code app.ai.memory.window} instead — see the applications' {@code
 * application.yaml} for what it costs.
 *
 * <p>Where this sits in the ordering is the whole of what makes it safe, and it has to be in one
 * exact place: <em>after</em> every repository auto-configuration and <em>before</em> {@code
 * ChatMemoryAutoConfiguration}.
 *
 * <ul>
 *   <li>After the repositories, because {@code RedisChatMemoryRepositoryAutoConfiguration} declares
 *       its repository {@code @ConditionalOnMissingBean({RedisChatMemoryRepository.class,
 *       ChatMemory.class, ChatMemoryRepository.class})} — a {@link ChatMemory} registered before it
 *       is asked makes it back off, and the conversations then go to the in-memory repository while
 *       every Redis setting still reads as configured. {@code ChatMemoryRedisTest} is what notices;
 *       it caught exactly this.
 *   <li>Before {@code ChatMemoryAutoConfiguration}, because its {@link ChatMemory} is
 *       {@code @ConditionalOnMissingBean} too, so whichever of the two is sorted first is the one
 *       that decides the window. Losing that race is silent — the memory works, and keeps twenty.
 * </ul>
 *
 * <p>Named as strings rather than as classes so that none of those modules has to be on core's
 * compile classpath, which none of them is; an auto-configuration named here and absent is simply
 * not there to order against.
 *
 * <p>Which repository the messages land in is otherwise untouched: injecting one here takes
 * whichever {@code app.persistence.type} put in the context, including the two this repository
 * supplies itself.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
      "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.MongoChatMemoryAutoConfiguration",
      "org.springframework.ai.model.chat.memory.repository.redis.autoconfigure.RedisChatMemoryRepositoryAutoConfiguration"
    },
    beforeName =
        "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration")
public class ChatMemoryConfiguration {

  /**
   * How many messages of a conversation are kept, and so replayed, unless a deployment says
   * otherwise.
   *
   * <p>Ten times Spring AI's twenty, because a message here is not a turn: the memory advisor sits
   * inside the tool-calling loop, so one question answered by reading three files is already eight
   * or nine messages — the question, the answer, and a call and a result for each tool. A window of
   * twenty is then two exchanges, and an agent that has forgotten what it was asked two questions
   * ago reads as broken rather than as configured low.
   *
   * <p>What a larger window costs is paid twice, which is why this is a number and not simply a
   * large one. The window is replayed into every request, so it is tokens on each turn; and {@link
   * MessageWindowChatMemory} trims by writing the whole kept window back, so it is also a store
   * write of up to this many messages per turn. Two hundred is where a day of conversation survives
   * and neither cost is yet remarkable.
   */
  public static final int DEFAULT_WINDOW = 200;

  @Bean
  @ConditionalOnMissingBean
  ChatMemory chatMemory(
      final ChatMemoryRepository chatMemoryRepository,
      @Value("${app.ai.memory.window:" + DEFAULT_WINDOW + "}") final int window) {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(chatMemoryRepository)
        .maxMessages(window)
        .build();
  }
}
