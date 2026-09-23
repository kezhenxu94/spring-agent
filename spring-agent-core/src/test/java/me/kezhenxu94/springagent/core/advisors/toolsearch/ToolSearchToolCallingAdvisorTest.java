package me.kezhenxu94.springagent.core.advisors.toolsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

/**
 * The one behaviour this fork changed from upstream: where the session-initialization suffix lands
 * when a run carries several {@link SystemMessage}s — one per configured system-prompt part —
 * rather than the single message every run used to carry. Everything else is upstream's own code,
 * copied rather than rewritten, and is exercised by upstream's own test suite rather than repeated
 * here.
 */
class ToolSearchToolCallingAdvisorTest {

  private final CallAdvisorChain chain = mock(CallAdvisorChain.class);

  private ToolSearchToolCallingAdvisor advisor() {
    return ToolSearchToolCallingAdvisor.builder()
        .toolCallingManager(ToolCallingManager.builder().build())
        .toolIndex(new StubToolIndex())
        .systemMessageSuffix("\n\nSEARCH FOR TOOLS")
        .sessionIdKeyName(ChatMemory.CONVERSATION_ID)
        .build();
  }

  private static ChatClientRequest turnWith(final List<Message> systemMessages) {
    final var messages = new java.util.ArrayList<>(systemMessages);
    messages.add(new UserMessage("what tools do you have"));
    return ChatClientRequest.builder()
        .prompt(new Prompt(messages, ToolCallingChatOptions.builder().build()))
        .context(Map.of(ChatMemory.CONVERSATION_ID, "conv-1"))
        .build();
  }

  private static List<SystemMessage> systemMessagesOf(final ChatClientRequest request) {
    return request.prompt().getInstructions().stream()
        .filter(SystemMessage.class::isInstance)
        .map(SystemMessage.class::cast)
        .toList();
  }

  @Test
  @DisplayName("with one system message, the suffix lands on it, as it always did")
  void oneSystemMessage() {
    final var result =
        advisor()
            .doInitializeLoop(turnWith(List.of(new SystemMessage("You are an agent."))), chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(1);
    assertThat(systemMessages.get(0).getText()).isEqualTo("You are an agent.\n\nSEARCH FOR TOOLS");
  }

  @Test
  @DisplayName(
      "with several system messages — one per configured system-prompt part — the suffix lands"
          + " on the last one, not the first")
  void appendsToTheLastOfSeveral() {
    final var result =
        advisor()
            .doInitializeLoop(
                turnWith(
                    List.of(
                        new SystemMessage("Static house rules."),
                        new SystemMessage("You are an agent."))),
                chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(2);
    // The static piece is untouched — this is the piece a deployment would want cached, and this
    // fork exists so that appending a per-session suffix does not disturb it.
    assertThat(systemMessages.get(0).getText()).isEqualTo("Static house rules.");
    assertThat(systemMessages.get(1).getText()).isEqualTo("You are an agent.\n\nSEARCH FOR TOOLS");
  }

  @Test
  @DisplayName("a request with no system message gets one rather than an NPE")
  void noSystemMessage() {
    final var result = advisor().doInitializeLoop(turnWith(List.of()), chain);

    final var systemMessages = systemMessagesOf(result);
    assertThat(systemMessages).hasSize(1);
    assertThat(systemMessages.get(0).getText()).isEqualTo("\n\nSEARCH FOR TOOLS");
  }

  /** Enough of {@link ToolIndex} for a session to initialize; searches are not exercised here. */
  private static final class StubToolIndex implements ToolIndex {
    @Override
    public void indexTool(final String sessionId, final ToolReference toolReference) {}

    @Override
    public ToolSearchResponse search(final ToolSearchRequest toolSearchRequest) {
      return ToolSearchResponse.builder().toolReferences(List.of()).build();
    }

    @Override
    public void clearIndex(final String sessionId) {}
  }
}
