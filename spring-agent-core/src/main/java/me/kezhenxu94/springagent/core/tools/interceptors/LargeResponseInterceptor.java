package me.kezhenxu94.springagent.core.tools.interceptors;

import com.google.common.base.Strings;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Diverts a tool result too large to put in front of the model into the caller's workspace, and
 * hands back a pointer to it instead.
 *
 * <p>The pointer is only worth anything if the file can be got at again, which is why what is
 * written is not always what the tool returned. A JSON result arrives as one line — that is what
 * serializing a tree produces — and the {@code Read} tool truncates any line past 2000 characters,
 * so a result written verbatim would be unreadable by the one tool every deployment has. It is
 * pretty-printed on the way to disk so that reading a slice of it means something. Shell is not an
 * answer either: {@code app.ai.tools.shell.type} is {@code none} by default, so a guide that says
 * to run jq is a guide to a tool that is not there.
 *
 * <p>The guide also describes the shape of what was saved — which keys, how long the arrays are —
 * because that is usually all the model needed. A listing it only wanted a count from costs one
 * line here instead of a second call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LargeResponseInterceptor implements ToolCallInterceptor {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * Indented rather than compact, so that a slice of the file read by line number is a slice of
   * something. See the class note: this is what makes the saved file readable at all.
   */
  private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

  /**
   * Every field an MCP text content item may carry. An item with any other field is a tool's own
   * data that happens to have a {@code text} in it — a search hit with its score — and is kept.
   */
  private static final Set<String> MCP_TEXT_CONTENT_FIELDS =
      Set.of("type", "text", "annotations", "meta", "_meta");

  /** How deep {@link #describe} goes before it stops naming what it found. */
  private static final int MAX_SHAPE_DEPTH = 2;

  /** How many of an object's keys the shape names before it says how many are left. */
  private static final int MAX_SHAPE_KEYS = 20;

  final SpringAgentProperties appConfiguration;
  final UserWorkspaceFactory userWorkspaceFactory;
  final CoreMessages messages;

  @Override
  @SneakyThrows
  public String afterCall(
      String toolName, String toolInput, String toolResult, ToolContext toolContext) {
    if (toolResult == null
        || toolResult.length() <= appConfiguration.ai().tools().maxResultChars()
        || readsMemoryIndex(toolName, toolInput)) {
      return toolResult;
    }

    final var size = toolResult.length();
    log.info(
        "Tool '{}' returned {} chars, max-result-chars={}",
        toolName,
        size,
        appConfiguration.ai().tools().maxResultChars());

    final var userId = ToolContexts.get(toolContext, ToolContexts.USER_ID);
    if (Strings.isNullOrEmpty(userId)) {
      log.warn(
          "Tool '{}' returned large result ({} chars) but no userId in ToolContext; "
              + "returning raw result without persisting.",
          toolName,
          size);
      return toolResult;
    }

    final var artifactsDir =
        userWorkspaceFactory.forRequest(toolContext).artifacts().resolve("tool-results");
    Files.createDirectories(artifactsDir);

    var json = parseOrNull(toolResult);
    // A tool returning a String arrives encoded as a JSON string: one line, quoted, its newlines
    // escaped. Written as it came, Grep skips the line once it passes its length limit and Read
    // truncates it, so the text itself is what goes to disk.
    var text = json == null ? textOf(toolResult) : null;
    // An MCP tool's result arrives as its content items, [{"text": "..."}], each text usually a
    // JSON document of its own as a string. Pretty-printing the wrapper leaves that document on one
    // escaped line, the same failure as above, so what is written is the texts themselves.
    final var contents = textContents(json);
    if (contents != null) {
      final var only = contents.size() == 1 ? parseOrNull(contents.getFirst()) : null;
      json = only;
      text =
          only != null
              ? null
              : contents.stream()
                  .map(LargeResponseInterceptor::prettyIfJson)
                  .collect(Collectors.joining("\n\n"));
    }

    // In the artifacts directory rather than the system one: createTempFile puts the file in the
    // directory it is given, and only the workspace is a place the Read tool is allowed to look.
    // The unique name is load-bearing rather than tidy — a model emits tool calls in parallel, and
    // a name made of the tool and the millisecond collides between two of them, which used to mean
    // one guide silently pointed at the other call's result.
    final var file =
        Files.createTempFile(artifactsDir, toolName + "-", json == null ? ".txt" : ".json");
    Files.writeString(file, json == null ? text : PRETTY.writeValueAsString(json));
    log.info("Saved large tool result for '{}' to: {}", toolName, file.toAbsolutePath());

    return messages.get(
        "tool-result-spilled", toolName, size, file.toAbsolutePath(), shapeOf(json, text));
  }

  /**
   * Whether this call reads a memory index. Never spilled: the index is what tells the model which
   * memory to open, and a pointer to a file in its place costs a round trip at best — at worst, as
   * happened, a model that never finds the line it needed.
   */
  private static boolean readsMemoryIndex(final String toolName, final String toolInput) {
    if (!"MemoryView".equals(toolName) || toolInput == null) {
      return false;
    }
    try {
      final var path = MAPPER.readTree(toolInput).path("path");
      return path.isString() && path.stringValue().strip().endsWith("MEMORY.md");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The texts of an MCP result whose every item is text content, or null for any other shape. An
   * item of another type — an image, a resource — means the wrapper is the result and is kept.
   */
  private static List<String> textContents(final JsonNode json) {
    if (json == null || !json.isArray() || json.isEmpty()) {
      return null;
    }
    final var texts = new ArrayList<String>(json.size());
    for (final var item : json) {
      final var type = item.get("type");
      if (!item.isObject()
          || !item.path("text").isString()
          || (type != null && !"text".equals(type.asString("")))
          || !MCP_TEXT_CONTENT_FIELDS.containsAll(item.propertyNames())) {
        return null;
      }
      texts.add(item.get("text").stringValue());
    }
    return texts;
  }

  /** {@code text} indented where it is a JSON object or array, and as it is otherwise. */
  private static String prettyIfJson(final String text) {
    final var json = parseOrNull(text);
    return json == null ? text : PRETTY.writeValueAsString(json);
  }

  /** {@code raw} decoded where it is a JSON string, and as it is otherwise. */
  private static String textOf(final String raw) {
    try {
      final var node = MAPPER.readTree(raw);
      return node != null && node.isString() ? node.stringValue() : raw;
    } catch (Exception e) {
      return raw;
    }
  }

  private static JsonNode parseOrNull(final String candidate) {
    try {
      final var node = MAPPER.readTree(candidate);
      // A bare scalar is JSON, but pretty-printing it changes nothing and calling it JSON in the
      // guide would send the model looking for keys that a plain string never had.
      return node != null && (node.isObject() || node.isArray()) ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** What is in the file, in one line: the keys and the array lengths, or the line count. */
  private String shapeOf(final JsonNode json, final String raw) {
    if (json == null) {
      return plainTextShape(raw);
    }
    return describe(json, 0);
  }

  private String plainTextShape(final String raw) {
    final var lines = raw.lines().count();
    return messages.get("large-response-text-lines", lines);
  }

  private String describe(final JsonNode node, final int depth) {
    if (node.isArray()) {
      if (node.isEmpty()) {
        return "[]";
      }
      if (depth >= MAX_SHAPE_DEPTH) {
        return messages.get("large-response-items", node.size());
      }
      return messages.get(
          "large-response-items-each", node.size(), describe(node.get(0), depth + 1));
    }
    if (node.isObject()) {
      if (depth >= MAX_SHAPE_DEPTH) {
        return messages.get("large-response-keys", node.size());
      }
      final var described = new StringBuilder("{");
      var shown = 0;
      for (final var property : node.properties()) {
        if (shown == MAX_SHAPE_KEYS) {
          described.append(messages.get("large-response-more-keys", node.size() - shown));
          break;
        }
        if (shown > 0) {
          described.append(", ");
        }
        described
            .append(property.getKey())
            .append(": ")
            .append(describe(property.getValue(), depth + 1));
        shown++;
      }
      return described.append("}").toString();
    }
    if (node.isString()) {
      return "text";
    }
    if (node.isNumber()) {
      return "number";
    }
    if (node.isBoolean()) {
      return "boolean";
    }
    return "null";
  }
}
