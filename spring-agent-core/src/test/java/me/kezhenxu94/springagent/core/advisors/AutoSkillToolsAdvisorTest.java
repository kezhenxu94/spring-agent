package me.kezhenxu94.springagent.core.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;

/**
 * What this advisor is for is a nudge that arrives while an expensive turn is still running, so
 * what these pin down is when it arrives and when it stays out of the way.
 */
class AutoSkillToolsAdvisorTest {

  private static final String PROMPT = "Offer a skill. This turn made {TOOL_CALL_COUNT} calls.";

  private final AdvisorChain chain = mock(AdvisorChain.class);

  private AutoSkillToolsAdvisor advisor(final int threshold) {
    return AutoSkillToolsAdvisor.builder()
        .toolCallThreshold(threshold)
        .skillSystemPrompt(new ByteArrayResource(PROMPT.getBytes()))
        .build();
  }

  /** A turn as the tool loop forwards it: a system message, the user's, then a round per call. */
  private static ChatClientRequest turnWith(final int toolCalls, final ChatOptions options) {
    final List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage("You are an agent."));
    messages.add(new UserMessage("do something expensive"));
    for (var i = 0; i < toolCalls; i++) {
      messages.add(
          AssistantMessage.builder()
              .content("")
              .toolCalls(
                  List.of(new AssistantMessage.ToolCall("id" + i, "function", "ReadFile", "{}")))
              .build());
    }
    return ChatClientRequest.builder().prompt(new Prompt(messages, options)).build();
  }

  private static ChatClientRequest turnWith(final int toolCalls) {
    return turnWith(toolCalls, ToolCallingChatOptions.builder().build());
  }

  private static String systemTextOf(final ChatClientRequest request) {
    return request.prompt().getSystemMessage().getText();
  }

  @Nested
  @DisplayName("when the turn is cheap")
  class Cheap {

    @Test
    @DisplayName("nothing is appended below the threshold")
    void belowThreshold() {
      final var request = turnWith(19);

      final var advised = advisor(20).before(request, chain);

      assertThat(advised).isSameAs(request);
      assertThat(systemTextOf(advised)).isEqualTo("You are an agent.");
    }

    @Test
    @DisplayName("a turn that has called nothing at all is left alone")
    void noToolCalls() {
      assertThat(advisor(20).before(turnWith(0), chain)).isNotNull();
      assertThat(systemTextOf(advisor(20).before(turnWith(0), chain)))
          .doesNotContain("Offer a skill");
    }
  }

  @Nested
  @DisplayName("when the turn has become expensive")
  class Expensive {

    @Test
    @DisplayName("the prompt is appended once the threshold is reached")
    void atThreshold() {
      final var advised = advisor(20).before(turnWith(20), chain);

      assertThat(systemTextOf(advised)).startsWith("You are an agent.").contains("Offer a skill");
    }

    @Test
    @DisplayName("the appended text names how many calls the turn has actually made")
    void carriesTheCount() {
      assertThat(systemTextOf(advisor(20).before(turnWith(34), chain))).contains("34 calls");
    }

    @Test
    @DisplayName("calls are summed across every assistant message of the turn")
    void countsEveryRound() {
      // Two rounds, three calls in the second: the count is calls, not rounds, because that is
      // what the turn cost.
      final List<Message> messages =
          List.of(
              new SystemMessage("You are an agent."),
              AssistantMessage.builder()
                  .content("")
                  .toolCalls(
                      List.of(new AssistantMessage.ToolCall("a", "function", "ReadFile", "{}")))
                  .build(),
              AssistantMessage.builder()
                  .content("")
                  .toolCalls(
                      List.of(
                          new AssistantMessage.ToolCall("b", "function", "ReadFile", "{}"),
                          new AssistantMessage.ToolCall("c", "function", "ReadFile", "{}")))
                  .build());
      final var request =
          ChatClientRequest.builder()
              .prompt(new Prompt(messages, ToolCallingChatOptions.builder().build()))
              .build();

      assertThat(systemTextOf(advisor(3).before(request, chain))).contains("3 calls");
    }

    @Test
    @DisplayName("an assistant message that called nothing counts for nothing")
    void plainAssistantMessages() {
      final var request =
          ChatClientRequest.builder()
              .prompt(
                  new Prompt(
                      List.of(new SystemMessage("You are an agent."), new AssistantMessage("hi")),
                      ToolCallingChatOptions.builder().build()))
              .build();

      assertThat(systemTextOf(advisor(1).before(request, chain))).doesNotContain("Offer a skill");
    }
  }

  @Nested
  @DisplayName("wiring")
  class Wiring {

    @Test
    @DisplayName("a prompt without tool calling options is passed through untouched")
    void noToolCallingOptions() {
      // Not a run this advisor has anything to say to: no tool calls to count, and no way to write
      // a skill either.
      final var request = turnWith(50, ChatOptions.builder().build());

      assertThat(advisor(20).before(request, chain)).isSameAs(request);
    }

    @Test
    @DisplayName("the response is handed back exactly as it arrived")
    void afterIsAPassthrough() {
      final var response = mock(org.springframework.ai.chat.client.ChatClientResponse.class);

      assertThat(advisor(20).after(response, chain)).isSameAs(response);
    }

    @Test
    @DisplayName("it defaults to running inside the tool calling loop")
    void defaultOrder() {
      // Outside the loop it would be called once per turn, before that turn had made a single tool
      // call, and could only ever react to the turn before.
      assertThat(advisor(20).getOrder()).isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
    }

    @Test
    @DisplayName("the builder refuses a threshold that would offer on every turn")
    void rejectsThresholds() {
      assertThatThrownBy(() -> AutoSkillToolsAdvisor.builder().toolCallThreshold(0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the builder refuses to be built without a prompt to append")
    void requiresAPrompt() {
      assertThatThrownBy(() -> AutoSkillToolsAdvisor.builder().build())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
