package me.kezhenxu94.springagent.events.situation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.events.tools.SituationTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What each triage attempt has done: every tool call of a run about a situation that ran and
 * returned, one line each, kept until the attempt is over.
 *
 * <p>For the retry of an attempt that failed partway. A retry starts the whole run again, and a run
 * that had already sent a message, written a record or commented on an issue would otherwise do it
 * again — the one thing a retry must not cost. {@code SituationSweeper} takes the lines when the
 * attempt finishes and hands them to the retry as the first thing it reads.
 *
 * <p>Keyed by the attempt's request id, and held only while it runs: whatever is taken is gone, so
 * nothing accumulates for runs that finish.
 */
@Component
public class SituationActions implements ToolCallInterceptor {

  /** How much of a call's arguments, and of its result, a line keeps. */
  private static final int ARGUMENTS_CHARS = 200;

  private static final int RESULT_CHARS = 150;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final Map<String, List<String>> byAttempt = new ConcurrentHashMap<>();

  @Override
  public void onCompleted(
      final String toolName,
      final String toolInput,
      final String toolResult,
      final ToolContext toolContext) {
    final var context = toolContext == null ? Map.<String, Object>of() : toolContext.getContext();
    if (!(context.get(SituationTools.KEY_SITUATION_ID) instanceof String)
        || !(context.get(ToolContexts.KEY_REQUEST_ID) instanceof String attempt)
        || attempt.isBlank()) {
      return;
    }
    byAttempt
        .computeIfAbsent(attempt, key -> java.util.Collections.synchronizedList(new ArrayList<>()))
        .add(line(toolName, toolInput, toolResult));
  }

  /** The lines {@code attempt} recorded, oldest first, forgotten as they are handed over. */
  public List<String> take(final String attempt) {
    final var lines = byAttempt.remove(attempt);
    if (lines == null) {
      return List.of();
    }
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }

  static String line(final String toolName, final String toolInput, final String toolResult) {
    return "- "
        + toolName
        + "("
        + cut(arguments(toolInput), ARGUMENTS_CHARS)
        + ") → "
        + cut(firstLine(toolResult), RESULT_CHARS);
  }

  /** The arguments without the display description, which says nothing the call did. */
  private static String arguments(final String toolInput) {
    if (toolInput == null) {
      return "";
    }
    try {
      if (MAPPER.readTree(toolInput) instanceof ObjectNode object) {
        object.remove(me.kezhenxu94.springagent.core.tools.DisplayDescription.FIELD);
        return MAPPER.writeValueAsString(object);
      }
    } catch (RuntimeException e) {
      // Written as it came where it is not an object.
    }
    return toolInput;
  }

  private static String firstLine(final String text) {
    if (text == null) {
      return "";
    }
    final var stripped = text.strip();
    final var end = stripped.indexOf('\n');
    return end < 0 ? stripped : stripped.substring(0, end);
  }

  private static String cut(final String text, final int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }
}
