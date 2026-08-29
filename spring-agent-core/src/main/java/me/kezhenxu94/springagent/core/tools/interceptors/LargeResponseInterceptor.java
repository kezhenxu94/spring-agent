package me.kezhenxu94.springagent.core.tools.interceptors;

import com.google.common.base.Strings;
import java.nio.file.Files;
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
        || toolResult.length() <= appConfiguration.ai().tools().maxResultChars()) {
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

    final var json = parseOrNull(toolResult);

    // In the artifacts directory rather than the system one: createTempFile puts the file in the
    // directory it is given, and only the workspace is a place the Read tool is allowed to look.
    // The unique name is load-bearing rather than tidy — a model emits tool calls in parallel, and
    // a name made of the tool and the millisecond collides between two of them, which used to mean
    // one guide silently pointed at the other call's result.
    final var file =
        Files.createTempFile(artifactsDir, toolName + "-", json == null ? ".txt" : ".json");
    Files.writeString(file, json == null ? toolResult : PRETTY.writeValueAsString(json));
    log.info("Saved large tool result for '{}' to: {}", toolName, file.toAbsolutePath());

    return messages.get(
        "tool-result-spilled", toolName, size, file.toAbsolutePath(), shapeOf(json, toolResult));
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
  private static String shapeOf(final JsonNode json, final String raw) {
    if (json == null) {
      return plainTextShape(raw);
    }
    return describe(json, 0);
  }

  private static String plainTextShape(final String raw) {
    final var lines = raw.lines().count();
    return "text, " + lines + " line(s)";
  }

  private static String describe(final JsonNode node, final int depth) {
    if (node.isArray()) {
      if (node.isEmpty()) {
        return "[]";
      }
      if (depth >= MAX_SHAPE_DEPTH) {
        return "[" + node.size() + " items]";
      }
      return "[" + node.size() + " items, each " + describe(node.get(0), depth + 1) + "]";
    }
    if (node.isObject()) {
      if (depth >= MAX_SHAPE_DEPTH) {
        return "{" + node.size() + " keys}";
      }
      final var described = new StringBuilder("{");
      var shown = 0;
      for (final var property : node.properties()) {
        if (shown == MAX_SHAPE_KEYS) {
          described.append(", and ").append(node.size() - shown).append(" more keys");
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
