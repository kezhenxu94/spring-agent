package me.kezhenxu94.springagent.core.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;

/**
 * All this advisor does is put one paragraph in front of the model, so what these pin down is that
 * it arrives, that it arrives once, and that it says which memories this run has.
 */
class MemoryToolsAdvisorTest {

  private static final String PROMPT = "You have a memory. It is here:\n{MEMORY_SCOPES}\n";

  private static final String SCOPES =
      """
      - own — /data/ou_1/memories — yours alone; you may write here
      - group — /data/groups/oc_9/memories — shared with this chat; you may write here\
      """;

  private final AdvisorChain chain = mock(AdvisorChain.class);

  private MemoryToolsAdvisor advisor() {
    return MemoryToolsAdvisor.builder()
        .memoryScopes(SCOPES)
        .memorySystemPrompt(new ByteArrayResource(PROMPT.getBytes()))
        .build();
  }

  private static ChatClientRequest turn(final ChatOptions options) {
    final List<Message> messages =
        List.of(new SystemMessage("You are an agent."), new UserMessage("remember this"));
    return ChatClientRequest.builder().prompt(new Prompt(messages, options)).build();
  }

  private static ChatClientRequest turn() {
    return turn(ToolCallingChatOptions.builder().build());
  }

  private static String systemTextOf(final ChatClientRequest request) {
    return request.prompt().getSystemMessage().getText();
  }

  @Nested
  @DisplayName("what reaches the model")
  class Appended {

    @Test
    @DisplayName("the scopes block is rendered into the system message")
    void scopesReachTheSystemMessage() {
      final var text = systemTextOf(advisor().before(turn(), chain));
      assertThat(text).startsWith("You are an agent.");
      assertThat(text).contains("You have a memory.").contains(SCOPES);
      // The placeholder itself must not survive, or the model reads the literal template.
      assertThat(text).doesNotContain("MEMORY_SCOPES");
    }

    @Test
    @DisplayName(
        "running the advisor twice appends the paragraph twice, so ordering has to keep it out of"
            + " the loop")
    void appendedOncePerCall() {
      final var once = systemTextOf(advisor().before(turn(), chain));
      final var twice = systemTextOf(advisor().before(advisor().before(turn(), chain), chain));
      // Not a defect being asserted, but the reason DEFAULT_ORDER is what it is: before() is not
      // idempotent, so the advisor has to run once a turn rather than once an iteration.
      assertThat(countOf(twice)).isEqualTo(2 * countOf(once));
    }

    private static int countOf(final String text) {
      return text.split("You have a memory\\.", -1).length - 1;
    }
  }

  @Nested
  @DisplayName("when there is nothing to append to")
  class Degenerate {

    @Test
    @DisplayName("a request with no tool calling options is left alone")
    void noToolOptions() {
      final var request = turn(ChatOptions.builder().build());
      assertThat(advisor().before(request, chain)).isSameAs(request);
    }

    @Test
    @DisplayName("a request with no system message does not throw")
    void noSystemMessage() {
      // Upstream reads getSystemMessage().getText() unguarded, and this is the case that made it
      // an NPE out of a Reactor operator with nothing in the trace pointing at the advisor.
      final var request =
          ChatClientRequest.builder()
              .prompt(
                  new Prompt(
                      List.of(new UserMessage("remember this")),
                      ToolCallingChatOptions.builder().build()))
              .build();
      final var text = systemTextOf(advisor().before(request, chain));
      assertThat(text).contains(SCOPES);
    }
  }

  @Nested
  @DisplayName("how it is assembled")
  class Building {

    @Test
    @DisplayName("it runs before the tool-calling loop")
    void order() {
      assertThat(advisor().getOrder()).isEqualTo(MemoryToolsAdvisor.DEFAULT_ORDER);
      assertThat(advisor().getOrder()).isLessThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    @Test
    @DisplayName("a response passes through untouched")
    void afterIsIdentity() {
      final var response = ChatClientResponse.builder().build();
      assertThat(advisor().after(response, chain)).isSameAs(response);
    }

    @Test
    @DisplayName("it refuses to be built without a prompt or without scopes")
    void refusesIncomplete() {
      assertThatThrownBy(
              () ->
                  MemoryToolsAdvisor.builder()
                      .memorySystemPrompt(new ByteArrayResource(PROMPT.getBytes()))
                      .build())
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> MemoryToolsAdvisor.builder().memoryScopes(SCOPES).build())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
