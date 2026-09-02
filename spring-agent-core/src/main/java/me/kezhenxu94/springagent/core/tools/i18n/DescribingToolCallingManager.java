package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Offers every tool the display parameter {@link DisplayDescription} describes, on its way out to
 * the model.
 *
 * <p>{@code resolveToolDefinitions} for the reasons {@link LocalizingToolCallingManager} sets out
 * at length: it is what a chat model builds the request's tool list from, and it reads the
 * callbacks off the request, so an {@code @AgentTool} bean, a library's tool, an MCP server's tool
 * and a consumer's own {@code ToolCallbackProvider} all pass through here alike. Adding the
 * parameter at composition time would miss most of them.
 *
 * <p>A decorator of its own, and the outer of the two that rewrite definitions, so that it has the
 * last word on the schema: ordered the other way, a module translating a parameter it happens to
 * call {@code _display_description} would be rewriting the runtime's own field.
 *
 * <p>Only the schema is touched. The tool search fingerprints the names and descriptions it is
 * handed and re-indexes the whole set when that fingerprint moves, so leaving both alone is what
 * keeps this from costing an embedding run per deployment.
 */
@Slf4j
@RequiredArgsConstructor
public class DescribingToolCallingManager implements ToolCallingManager {

  /** What the parameter's own description is asked for under, in the workspace's language. */
  public static final String DESCRIPTION_KEY = "tool-display-description";

  private final ToolCallingManager delegate;
  private final CoreMessages messages;

  @Override
  public List<ToolDefinition> resolveToolDefinitions(final ToolCallingChatOptions chatOptions) {
    final var described = messages.get(DESCRIPTION_KEY);
    return delegate.resolveToolDefinitions(chatOptions).stream()
        .map(definition -> describe(definition, described))
        .toList();
  }

  /** Nothing to add here; the parameter is offered to the model and taken back off the call. */
  @Override
  public ToolExecutionResult executeToolCalls(
      final Prompt prompt, final ChatResponse chatResponse) {
    return delegate.executeToolCalls(prompt, chatResponse);
  }

  /**
   * The same definition with the parameter offered, or — where the tool asks for one of its own, or
   * has no parameters to add to — the very object that was passed in.
   */
  private static ToolDefinition describe(final ToolDefinition definition, final String described) {
    final var schema =
        DisplayDescription.inject(definition.name(), definition.inputSchema(), described);
    if (schema.equals(definition.inputSchema())) {
      return definition;
    }
    return ToolDefinition.builder()
        .name(definition.name())
        .description(definition.description())
        .inputSchema(schema)
        .build();
  }
}
