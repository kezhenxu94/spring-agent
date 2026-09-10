package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * How much of a conversation survives into the next turn.
 *
 * <p>Asserted by adding messages and reading them back rather than by inspecting the bean, because
 * {@code MessageWindowChatMemory} keeps its window private — and because the window only matters as
 * what a conversation still holds.
 */
class ChatMemoryConfigurationTest {

  private static final String CONVERSATION = "a-conversation";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChatMemoryConfiguration.class))
          .withBean(ChatMemoryRepository.class, InMemoryChatMemoryRepository::new);

  @Test
  @DisplayName("a conversation keeps two hundred messages, not Spring AI's twenty")
  void theDefaultWindow() {
    // Twenty is about two exchanges of a tool-calling agent, which is the reason this bean exists
    // at all: upstream builds the memory with that number and reads no property on the way.
    runner.run(context -> assertThat(kept(context.getBean(ChatMemory.class), 300)).isEqualTo(200));
  }

  @Test
  @DisplayName("a deployment states its own window")
  void theWindowIsConfigurable() {
    runner
        .withPropertyValues("app.ai.memory.window=5")
        .run(context -> assertThat(kept(context.getBean(ChatMemory.class), 300)).isEqualTo(5));
  }

  @Test
  @DisplayName("this window is the one a deployment gets, with upstream's own bean beside it")
  void thisConfigurationSortsFirst() {
    // Both declarations are @ConditionalOnMissingBean, so whichever auto-configuration is sorted
    // first decides the window — which is what the beforeName on this class states rather than
    // leaves to the alphabetical fallback. Losing that race is silent: the context starts, the
    // memory works, and it keeps twenty messages.
    runner
        .withConfiguration(AutoConfigurations.of(ChatMemoryAutoConfiguration.class))
        .run(context -> assertThat(kept(context.getBean(ChatMemory.class), 300)).isEqualTo(200));
  }

  @Test
  @DisplayName("an application's own ChatMemory wins, as it did before this bean existed")
  void anApplicationsOwnMemoryWins() {
    final var ownMemory = new RecordingChatMemory();
    runner
        .withBean(ChatMemory.class, () -> ownMemory)
        .run(context -> assertThat(context.getBean(ChatMemory.class)).isSameAs(ownMemory));
  }

  @Test
  @DisplayName("the messages go to the repository the context has, not to a fresh in-memory one")
  void theContextsRepositoryIsWhatStores() {
    // The builder defaults to an InMemoryChatMemoryRepository of its own, so forgetting to hand it
    // the context's would be a deployment that stores its conversations nowhere while every
    // persistence setting still reads as configured.
    final var repository = new InMemoryChatMemoryRepository();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChatMemoryConfiguration.class))
        .withBean(ChatMemoryRepository.class, () -> repository)
        .run(
            context -> {
              context.getBean(ChatMemory.class).add(CONVERSATION, List.of(new UserMessage("hi")));
              assertThat(repository.findByConversationId(CONVERSATION)).hasSize(1);
            });
  }

  /** Adds {@code count} messages to a fresh conversation and says how many of them are left. */
  private static int kept(final ChatMemory memory, final int count) {
    final var conversation = CONVERSATION + "-" + count + "-" + System.nanoTime();
    memory.add(
        conversation,
        IntStream.range(0, count).<Message>mapToObj(i -> new UserMessage("message " + i)).toList());
    return memory.get(conversation).size();
  }

  /** A memory an application might declare instead; only its identity is asserted. */
  private static class RecordingChatMemory implements ChatMemory {
    @Override
    public void add(final String conversationId, final List<Message> messages) {}

    @Override
    public List<Message> get(final String conversationId) {
      return List.of();
    }

    @Override
    public void clear(final String conversationId) {}
  }
}
