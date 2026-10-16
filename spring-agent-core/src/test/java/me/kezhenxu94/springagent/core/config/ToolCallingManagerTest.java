package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The tool-calling manager the application actually registers, rather than one a test builds by
 * hand. Spring AI 2.0.1 stopped consulting the resolver for a tool the request does not carry
 * unless told to, and nothing else notices the flag going missing: every other test builds its own
 * manager, and the run only fails once the tool search has hidden a tool the model calls anyway.
 */
class ToolCallingManagerTest {

  @Test
  @DisplayName("a call to a tool this iteration was not offered is still resolved by name")
  void resolvesAToolTheRequestDoesNotCarry() {
    final var asked = new ArrayList<String>();
    final var manager =
        new SpringAgentCoreAutoConfiguration()
            .toolCallingManager(
                toolName -> {
                  asked.add(toolName);
                  return null;
                },
                List.of(),
                new ToolInputFileRefs(null, null, null, List.of()),
                List.of(),
                new ToolCallingProperties(),
                new StaticListableBeanFactory()
                    .getBeanProvider(ToolExecutionExceptionProcessor.class),
                TestI18n.english());
    final var toolCall =
        new AssistantMessage.ToolCall("call_1", "function", "hiddenBySearch", "{}");
    final var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
    final var prompt =
        new Prompt(
            List.of(new UserMessage("hi")),
            // What the tool-search advisor leaves a run offering before the model has searched.
            ToolCallingChatOptions.builder().toolCallbacks(offered("toolSearchTool")).build());

    final var last = manager.executeToolCalls(prompt, response).conversationHistory().getLast();

    assertThat(asked).containsExactly("hiddenBySearch");
    assertThat(((ToolResponseMessage) last).getResponses())
        .singleElement()
        .satisfies(answer -> assertThat(answer.responseData()).contains("hiddenBySearch"));
  }

  private static ToolCallback offered(final String name) {
    final var definition =
        ToolDefinition.builder().name(name).description("").inputSchema("{}").build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(final String toolInput) {
        return "";
      }
    };
  }
}
