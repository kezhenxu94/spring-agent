package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.List;
import java.util.Map;
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
    final var call = new InterceptingToolCallback(target, interceptors, fileRefs, messages);
    return toolContext == null
        ? call.call(arguments(root))
        : call.call(arguments(root), toolContext);
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

  /**
   * The target's arguments as JSON. A model sometimes writes the object as a string, which is
   * unwrapped rather than refused. The display description is moved onto the target's arguments
   * where the model wrote it beside them instead, since the target's call is the one a surface
   * shows.
   */
  private static String arguments(final JsonNode root) {
    final var arguments = root.get("arguments");
    if (arguments != null && arguments.isString()) {
      return arguments.stringValue();
    }
    if (!(arguments instanceof ObjectNode object)) {
      return "{}";
    }
    final var described = root.get(DisplayDescription.FIELD);
    if (described != null && !object.has(DisplayDescription.FIELD)) {
      object.set(DisplayDescription.FIELD, described);
    }
    return MAPPER.writeValueAsString(object);
  }
}
