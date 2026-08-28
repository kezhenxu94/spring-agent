package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serves every tool's description and parameter descriptions in the workspace's language, on their
 * way to the model.
 *
 * <p>{@code @Tool} and {@code @ToolParam} carry that text, and an annotation attribute is a
 * compile-time constant: there is nowhere in a tool class for a translation to be read from. So it
 * is replaced afterwards, on the {@link ToolDefinition}, which is an interface over just a name, a
 * description and an input schema and can therefore be rebuilt.
 *
 * <p>{@code resolveToolDefinitions} is the one place worth doing it, and covers everything:
 *
 * <ul>
 *   <li>it is what a chat model builds the request's tool list from, so it is what the model reads;
 *   <li>it is also what the tool-search advisor indexes, so a localized description is embedded and
 *       searchable in the same language the user is writing in, with no index migration to run;
 *   <li>it reads the callbacks off the request, and a {@code ChatClient} derives one for every tool
 *       object it was given — so an {@code @AgentTool} bean, a library's tool, an MCP server's tool
 *       and a consumer's own {@code ToolCallbackProvider} all pass through here alike. Wrapping at
 *       composition time instead would miss the tool search's own tool, the stub the resolver
 *       synthesizes for a name it cannot place, and anything a consumer adds to a client of its
 *       own.
 * </ul>
 *
 * <p>A decorator of its own rather than another job for {@code InterceptingToolCallingManager},
 * which already wraps callbacks for interception, adds mid-run user messages and logs tools called
 * but not offered.
 *
 * <p><b>The name is never touched.</b> It is an identifier, not prose: subagent definitions filter
 * the tools they allow by name, the tool search returns names, interceptors dispatch on names, and
 * the run logs which offered names a call did not match. Only the two prose fields are rewritten.
 */
@Slf4j
@RequiredArgsConstructor
public class LocalizingToolCallingManager implements ToolCallingManager {

  /** JSON in a schema, not in a document: no application configuration should reach it. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String PROPERTIES = "properties";
  private static final String DESCRIPTION = "description";

  private final ToolCallingManager delegate;

  /** One per module that has anything translated; asked in order, the first answer winning. */
  private final List<ToolTexts> toolTexts;

  @Override
  public List<ToolDefinition> resolveToolDefinitions(final ToolCallingChatOptions chatOptions) {
    final var resolved = delegate.resolveToolDefinitions(chatOptions);
    final var localized = resolved.stream().map(this::localize).toList();
    if (log.isDebugEnabled()) {
      // What the model is actually being offered this iteration, and how much of it speaks the
      // workspace's language.
      //
      // This is the only place that can say so. Spring AI's SimpleLoggerAdvisor dumps the
      // ChatClientRequest from inside the advisor chain, which is before any of this runs: the
      // callbacks it prints still carry the definition their annotations declare, so it reads as
      // English whatever the locale, and it cannot show the tool search's own tool or the stub the
      // resolver synthesizes either. Localization happens here because here is where a chat model
      // asks for the definitions, and here is the last word on them.
      //
      // Worth logging at all because the tool search rebuilds the tool set on every iteration, so
      // "the tools are still in English" is usually a question about which tools were offered.
      final var untranslated =
          java.util.stream.IntStream.range(0, resolved.size())
              .filter(index -> localized.get(index) == resolved.get(index))
              .mapToObj(index -> resolved.get(index).name())
              .toList();
      log.debug(
          "Offered {} tool(s), {} localized; untranslated: {}",
          resolved.size(),
          resolved.size() - untranslated.size(),
          untranslated);

      // And, at TRACE, the definitions themselves — the text that goes on the wire, which is what
      // anyone asking "why is this still English" actually wants to read.
      if (log.isTraceEnabled()) {
        for (final var definition : localized) {
          log.trace(
              "Offered tool {}: description={} inputSchema={}",
              definition.name(),
              definition.description(),
              definition.inputSchema());
        }
      }
    }
    return localized;
  }

  /** Nothing to translate here; the definitions are the model's business, the calls are not. */
  @Override
  public ToolExecutionResult executeToolCalls(
      final Prompt prompt, final ChatResponse chatResponse) {
    return delegate.executeToolCalls(prompt, chatResponse);
  }

  /**
   * The same definition in the workspace's language, or — where nothing about the tool is
   * translated — the very object that was passed in.
   *
   * <p>Returning the argument matters twice over. It is the fast path, and with every tool of every
   * MCP server a user has registered arriving here on every request, the common case has to cost a
   * hash lookup and no allocation. And it is what keeps the tool search's fingerprint still: that
   * fingerprint is a hash of the names and descriptions handed over, and re-indexing is what
   * happens when it moves.
   */
  private ToolDefinition localize(final ToolDefinition definition) {
    final var name = definition.name();
    final var texts = textsFor(name);
    if (texts == null) {
      return definition;
    }
    final var description = texts.description(name);
    final var schema = localizedSchema(name, definition.inputSchema(), texts);
    if (description == null && schema.equals(definition.inputSchema())) {
      return definition;
    }
    return ToolDefinition.builder()
        .name(name)
        .description(description == null ? definition.description() : description)
        .inputSchema(schema)
        .build();
  }

  private ToolTexts textsFor(final String toolName) {
    ToolTexts found = null;
    for (final var texts : toolTexts) {
      if (!texts.covers(toolName)) {
        continue;
      }
      if (found == null) {
        found = texts;
        continue;
      }
      // Two modules translating one name, which is a real possibility rather than a theoretical
      // one:
      // every shell backend calls its tool Bash. Only one of them can win, and which one depends on
      // bean order, so the wrong wording would be served with nothing to say so. Each backend's
      // translations live in that backend's own module, conditional on it being the active one,
      // precisely so this cannot happen — and this says so if it ever does.
      log.warn(
          "More than one source translates '{}'; using the first. Two modules that can be active at"
              + " once must not both name it.",
          toolName);
      break;
    }
    return found;
  }

  /**
   * The schema with the parameter descriptions this module translates, or the string it was given.
   *
   * <p>A parameter's description sits on the property node itself, beside its type or its {@code
   * $ref}, so this needs no reference to follow. Jackson's object nodes keep insertion order and
   * replace a key in place, so {@code $schema}, {@code $defs}, {@code required} and {@code
   * additionalProperties} are neither reordered nor visited.
   *
   * <p>Only the tool's own parameters, not the fields of a type one of them takes: everything this
   * repository declares is a parameter of the method, and a key naming a field nested inside a
   * shared definition would rewrite it for every parameter that refers to it. The test that every
   * key names a real parameter is what stops one being added in the belief that it does something.
   */
  private String localizedSchema(
      final String toolName, final String schema, final ToolTexts texts) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(schema);
    } catch (JacksonException e) {
      // Losing a translation beats losing the tool: the request carries the schema either way.
      log.warn("Could not parse the input schema of '{}'; left as it is", toolName, e);
      return schema;
    }
    if (!(root instanceof ObjectNode object)
        || !(object.get(PROPERTIES) instanceof ObjectNode properties)) {
      // A tool that takes nothing, and the "{}" of the stub for a tool that could not be placed.
      return schema;
    }
    var changed = false;
    for (final var parameter : List.copyOf(properties.propertyNames())) {
      if (!(properties.get(parameter) instanceof ObjectNode property)) {
        continue;
      }
      final var translated = texts.parameter(toolName, parameter);
      if (translated != null) {
        property.put(DESCRIPTION, translated);
        changed = true;
      }
    }
    return changed ? object.toPrettyString() : schema;
  }
}
