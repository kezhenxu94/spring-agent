package me.kezhenxu94.springagent.core.tools.interceptors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * That every tool response a turn produces is JSON, including the ones no tool produced.
 *
 * <p>{@code InterceptingToolCallback} covers this project's own substitutions. Two more are made
 * inside {@code DefaultToolCallingManager}, beyond any callback: a thrown tool becomes {@code
 * toolExecutionExceptionProcessor.process(ex)} and a run over the tool-call limit becomes {@code
 * limitBreach.message()}. An MCP server that fails is the ordinary way to meet the first — {@code
 * Error calling tool: [TextContent[...]]} — and it ended runs on Gemini with {@code Stream
 * processing failed}, losing the error it was trying to report.
 */
class ToolResponseJsonTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A manager that answers the way Spring AI's does when a tool threw: with prose. */
  private static ToolCallingManager answering(final String responseData) {
    return new ToolCallingManager() {
      @Override
      public List<ToolDefinition> resolveToolDefinitions(final ToolCallingChatOptions options) {
        return List.of();
      }

      @Override
      public ToolExecutionResult executeToolCalls(
          final Prompt prompt, final ChatResponse chatResponse) {
        return ToolExecutionResult.builder()
            .conversationHistory(
                List.<Message>of(
                    new AssistantMessage("calling"),
                    ToolResponseMessage.builder()
                        .responses(
                            List.of(
                                new ToolResponseMessage.ToolResponse(
                                    "call_1", "github_search", responseData)))
                        .build()))
            .build();
      }
    };
  }

  private static String responseOf(final ToolExecutionResult result) {
    return result.conversationHistory().stream()
        .filter(ToolResponseMessage.class::isInstance)
        .map(ToolResponseMessage.class::cast)
        .flatMap(message -> message.getResponses().stream())
        .map(ToolResponseMessage.ToolResponse::responseData)
        .findFirst()
        .orElseThrow();
  }

  private static ToolExecutionResult run(final String responseData) {
    return new InterceptingToolCallingManager(answering(responseData), List.of(), null, null)
        .executeToolCalls(new Prompt("hi"), new ChatResponse(List.of()));
  }

  @Test
  @DisplayName("a tool that threw is reported as JSON, so the error survives to the model")
  void aThrownToolIsJson() {
    // The exact shape from production: Spring AI's exception processor, wrapping an MCP failure.
    final var prose =
        "Error calling tool: [TextContent[annotations=null, text=failed to search pull requests:"
            + " 422 Validation Failed, meta=null]]";

    final var encoded = responseOf(run(prose));

    assertThatCode(() -> MAPPER.readValue(encoded, Object.class)).doesNotThrowAnyException();
    // And it still says what went wrong: encoding must not cost the model the reason.
    assertThat(MAPPER.readValue(encoded, Object.class)).isEqualTo(prose);
  }

  @Test
  @DisplayName("a tool-call limit breach is reported as JSON too")
  void aLimitBreachIsJson() {
    // The other producer inside DefaultToolCallingManager, which never calls a callback at all.
    final var encoded = responseOf(run("You have reached the limit of 150 tool calls."));

    assertThatCode(() -> MAPPER.readValue(encoded, Object.class)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a response that is already JSON is left exactly as it was")
  void jsonIsUntouched() {
    // The ordinary turn: the converter has already run, so re-encoding would reach the model as a
    // quoted blob of text where an object was expected.
    assertThat(responseOf(run("{\"items\":[1,2]}"))).isEqualTo("{\"items\":[1,2]}");
    assertThat(responseOf(run("\"already quoted\""))).isEqualTo("\"already quoted\"");
  }
}
