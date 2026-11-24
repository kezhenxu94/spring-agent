package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.core.support.TestI18n;
import me.kezhenxu94.springagent.core.tools.interceptors.DispatchingToolCallback;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
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

    // Asked at least once, and only about the call: Spring AI resolves every call of a batch twice,
    // once to read its metadata for the confirmation policy before anything runs and again to run
    // it, so the count says nothing about whether resolution by name is on.
    assertThat(asked).isNotEmpty().containsOnly("hiddenBySearch");
    assertThat(((ToolResponseMessage) last).getResponses())
        .singleElement()
        .satisfies(answer -> assertThat(answer.responseData()).contains("hiddenBySearch"));
  }

  /** An interceptor recording the name of every call it sees. */
  private static ToolCallInterceptor seeing(final List<String> seen) {
    return new ToolCallInterceptor() {
      @Override
      public String beforeCall(
          final String toolName, final String toolInput, final ToolContext toolContext) {
        seen.add(toolName);
        return toolInput;
      }
    };
  }

  /** A deferred tool taking one required {@code text}, recording what it was called with. */
  private static final class Deferred implements ToolCallback {
    private String received;

    @Override
    public ToolDefinition getToolDefinition() {
      return ToolDefinition.builder()
          .name("IndexNote")
          .description("")
          .inputSchema(
              "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                  + "\"required\":[\"text\"]}")
          .build();
    }

    @Override
    public String call(final String toolInput) {
      received = toolInput;
      return "\"indexed\"";
    }
  }

  /**
   * What the model reads back for a call named {@code name}, made through the production manager on
   * a run offering what a tool search leaves it: the search and a CallTool reaching {@code
   * deferred}. The two share their interceptors, as the application's beans do.
   */
  private static String answerTo(
      final List<String> seen,
      final ToolCallback deferred,
      final String name,
      final String arguments) {
    final var interceptors = List.of(seeing(seen));
    final var refs = new ToolInputFileRefs(null, null, null, List.of());
    final var manager =
        new SpringAgentCoreAutoConfiguration()
            .toolCallingManager(
                toolName -> null,
                interceptors,
                refs,
                List.of(),
                new ToolCallingProperties(),
                new StaticListableBeanFactory()
                    .getBeanProvider(ToolExecutionExceptionProcessor.class),
                TestI18n.english());
    final var dispatcher =
        new DispatchingToolCallback(
            Map.of("IndexNote", deferred), interceptors, refs, TestI18n.english());
    final var toolCall = new AssistantMessage.ToolCall("call_1", "function", name, arguments);
    final var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build())));
    final var prompt =
        new Prompt(
            List.of(new UserMessage("hi")),
            ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(offered("toolSearchTool"), dispatcher))
                .build());
    final var last = manager.executeToolCalls(prompt, response).conversationHistory().getLast();
    return ((ToolResponseMessage) last).getResponses().getFirst().responseData();
  }

  @Test
  @DisplayName(
      "a tool the catalogue lists, called by its own name rather than through CallTool, runs as"
          + " CallTool would run it, under its real name")
  void runsADirectCallToACataloguedTool() {
    final var seen = new ArrayList<String>();
    final var deferred = new Deferred();

    final var answer = answerTo(seen, deferred, "IndexNote", "{\"text\":\"hello\"}");

    assertThat(deferred.received).isEqualTo("{\"text\":\"hello\"}");
    assertThat(answer).isEqualTo("\"indexed\"");
    assertThat(seen).containsExactly("IndexNote");
  }

  @Test
  @DisplayName("and its arguments are checked as CallTool checks them")
  void checksADirectCallsArguments() {
    final var deferred = new Deferred();

    final var answer = answerTo(new ArrayList<>(), deferred, "IndexNote", "{\"body\":\"hello\"}");

    assertThat(deferred.received).isNull();
    assertThat(answer).contains("missing required").contains("unknown");
  }

  @Test
  @DisplayName("a name in neither the request nor the catalogue still gets the stub")
  void anUnknownNameStillGetsTheStub() {
    final var deferred = new Deferred();

    final var answer = answerTo(new ArrayList<>(), deferred, "NoSuchTool", "{}");

    assertThat(deferred.received).isNull();
    assertThat(answer).contains("NoSuchTool").contains("is not attached to you");
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
