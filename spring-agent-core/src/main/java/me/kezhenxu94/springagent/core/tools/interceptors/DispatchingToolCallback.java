package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code CallTool}: calls, by name, a tool that is not in the request's {@code tools} array.
 *
 * <p>It exists so that the {@code tools} array can stay the same for a whole conversation. On a
 * provider that caches by prefix — DeepSeek, and every OpenAI-compatible gateway in front of it —
 * the tool definitions are rendered ahead of the system prompt, so attaching one more tool
 * mid-conversation misses the cache for everything, history included. With this, a tool found by
 * {@code toolSearchTool} is never attached: its schema arrives in the search result, which sits in
 * the message history where growth only ever happens at the end, and the model calls it through
 * here.
 *
 * <p>The target runs wrapped in an {@link InterceptingToolCallback} of its own, under its own name
 * and with its own arguments, so every interceptor — a card showing the call, an access check keyed
 * on a tool's name, the spill of a large result — sees the call it would have seen had the tool
 * been attached. That is why {@link InterceptingToolCallingManager} leaves this one unwrapped: an
 * interceptor would otherwise see each call twice, once as {@code CallTool}.
 */
@Slf4j
public final class DispatchingToolCallback implements ToolCallback {

  public static final String NAME = "CallTool";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final ToolDefinition DEFINITION =
      ToolDefinition.builder()
          .name(NAME)
          .description(
              """
              Call one of the tools listed under "Tools you call through CallTool" in the system \
              prompt, or returned by toolSearchTool. Give its exact name, and its arguments as an \
              object matching its input_schema; toolSearchTool returns that schema when you do not \
              know it.\
              """)
          .inputSchema(
              """
              {"type":"object","properties":{\
              "name":{"type":"string","description":"The tool's exact name."},\
              "arguments":{"type":"object","description":"The tool's arguments, matching its input_schema."}},\
              "required":["name","arguments"]}\
              """)
          .build();

  private final Map<String, ToolCallback> targets;
  private final List<ToolCallInterceptor> interceptors;
  private final ToolInputFileRefs fileRefs;
  private final CoreMessages messages;

  /**
   * @param targets the tools this run may call through here, by name
   */
  public DispatchingToolCallback(
      final Map<String, ToolCallback> targets,
      final List<ToolCallInterceptor> interceptors,
      final ToolInputFileRefs fileRefs,
      final CoreMessages messages) {
    this.targets = Map.copyOf(targets);
    this.interceptors = interceptors;
    this.fileRefs = fileRefs;
    this.messages = messages;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return DEFINITION;
  }

  @Override
  public String call(final String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(final String toolInput, final ToolContext toolContext) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(toolInput == null ? "" : toolInput);
    } catch (JacksonException e) {
      return ToolResultJson.asJson(
          messages.get("tool-arguments-malformed", NAME, e.getOriginalMessage()));
    }
    final var name = root.path("name").isString() ? root.path("name").stringValue().strip() : "";
    final var candidates = resolve(name);
    if (candidates.size() != 1) {
      log.info("CallTool named '{}', which matches {} of this run's tools", name, candidates);
      return ToolResultJson.asJson(
          candidates.isEmpty()
              ? messages.get("call-tool-unknown", name)
              : messages.get("call-tool-ambiguous", name, String.join(", ", candidates)));
    }
    final var target = targets.get(candidates.getFirst());
    final var arguments = argumentsOf(root);
    if (arguments.object() != null) {
      final var schema = target.getToolDefinition().inputSchema();
      final var problems = mismatches(arguments.object(), schema);
      if (!problems.isEmpty()) {
        log.info("CallTool refused '{}': {}", candidates.getFirst(), problems);
        return ToolResultJson.asJson(
            messages.get(
                "call-tool-arguments-mismatch",
                candidates.getFirst(),
                String.join("; ", problems),
                compact(schema)));
      }
    }
    final var call = new InterceptingToolCallback(target, interceptors, fileRefs, messages);
    return toolContext == null
        ? call.call(arguments.json())
        : call.call(arguments.json(), toolContext);
  }

  /** What {@code CallTool}'s own input holds besides the target's arguments. */
  private static final Set<String> RESERVED =
      Set.of("name", "arguments", "_arguments", DisplayDescription.FIELD);

  /**
   * The target's arguments: an object, or — where the model wrote them as a string that will not
   * parse — that string as it is, for the target's own call to report.
   */
  private record Arguments(ObjectNode object, String json) {}

  /**
   * The target's arguments, wherever the model put them.
   *
   * <p>{@code arguments} is the schema's; {@code _arguments} is the same thing in the style of the
   * {@code _display_description} beside it, which models copy; and with neither, the model wrote
   * the arguments where {@code CallTool}'s own are, so whatever is not one of those is taken as
   * them. A model writing the object as a string has it unwrapped rather than refused. The display
   * description is moved onto the target's arguments, since the target's call is the one a surface
   * shows.
   */
  private static Arguments argumentsOf(final JsonNode root) {
    final var given = root.has("arguments") ? root.get("arguments") : root.get("_arguments");
    ObjectNode object;
    if (given != null && given.isString()) {
      try {
        object =
            MAPPER.readTree(given.stringValue()) instanceof ObjectNode parsed
                ? parsed
                : MAPPER.createObjectNode();
      } catch (JacksonException e) {
        return new Arguments(null, given.stringValue());
      }
    } else if (given instanceof ObjectNode provided) {
      object = provided;
    } else {
      object = MAPPER.createObjectNode();
      if (given == null) {
        for (final var field : root.properties()) {
          if (!RESERVED.contains(field.getKey())) {
            object.set(field.getKey(), field.getValue());
          }
        }
      }
    }
    final var described = root.get(DisplayDescription.FIELD);
    if (described != null && !object.has(DisplayDescription.FIELD)) {
      object.set(DisplayDescription.FIELD, described);
    }
    return new Arguments(object, MAPPER.writeValueAsString(object));
  }

  /**
   * What is wrong with {@code arguments} for a tool of {@code schema}: each required property left
   * out, and each one the schema does not have — unless it allows others. A model that has not seen
   * the schema guesses at names, and a call made anyway reaches the tool with what it needs missing
   * and the guess ignored: a refusal that does not say why, or, worse, an empty query answered as
   * if it were one. The display description is the runtime's and never counts.
   */
  static List<String> mismatches(final ObjectNode arguments, final String schema) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(schema == null ? "{}" : schema);
    } catch (JacksonException e) {
      return List.of();
    }
    final var problems = new ArrayList<String>();
    for (final var required : root.path("required")) {
      final var property = required.asString("");
      final var value = arguments.get(property);
      if (!property.isEmpty()
          && !DisplayDescription.FIELD.equals(property)
          && (value == null || value.isNull())) {
        problems.add("missing required \"" + property + "\"");
      }
    }
    final var properties = root.get("properties");
    final var additional = root.get("additionalProperties");
    final var open = additional != null && (additional.isObject() || additional.asBoolean(false));
    if (properties instanceof ObjectNode declared && !open) {
      for (final var name : arguments.propertyNames()) {
        if (!DisplayDescription.FIELD.equals(name) && !declared.has(name)) {
          problems.add("unknown \"" + name + "\"");
        }
      }
    }
    return problems;
  }

  /** {@code schema} on one line and without its {@code $schema}, as the model is shown it. */
  private static String compact(final String schema) {
    try {
      final var root = MAPPER.readTree(schema);
      if (root instanceof ObjectNode object) {
        object.remove("$schema");
      }
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException e) {
      return schema;
    }
  }

  /**
   * The tools {@code name} can mean, in every form the catalogue in the system prompt shows one:
   * the whole name; a group's {@code prefix{short}} as written there; or a short name alone, where
   * it ends exactly one tool's name after an underscore. A model copies what it reads, and the
   * catalogue lists a group's tools by what follows their shared prefix.
   */
  private List<String> resolve(final String name) {
    if (name.isEmpty()) {
      return List.of();
    }
    if (targets.containsKey(name)) {
      return List.of(name);
    }
    final var braces = BRACED.matcher(name);
    if (braces.matches()) {
      final var whole = braces.group(1) + braces.group(2).strip();
      return targets.containsKey(whole) ? List.of(whole) : List.of();
    }
    return targets.keySet().stream().filter(tool -> tool.endsWith("_" + name)).sorted().toList();
  }

  /** {@code git_{search}}: a prefix, then one short name in braces. */
  private static final java.util.regex.Pattern BRACED =
      java.util.regex.Pattern.compile("([^{}]*)\\{([^{},]+)}");
}
