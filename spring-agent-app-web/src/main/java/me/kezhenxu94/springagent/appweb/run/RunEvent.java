package me.kezhenxu94.springagent.appweb.run;

import java.util.Map;

/**
 * One thing that happened in a run, as the browser will read it.
 *
 * @param seq its position in the run's journal, and the cursor a reconnecting browser resumes from.
 *     Assigned by {@link RunJournal}, never by whoever built the event
 * @param type the SSE event name, which is what the frontend switches on
 * @param data the payload, serialized to JSON on the way out
 */
public record RunEvent(long seq, String type, Map<String, Object> data) {

  public static final String CONTENT = "content";
  public static final String REASONING = "reasoning";
  public static final String TOOL = "tool";
  public static final String TOOL_RESULT = "tool-result";
  public static final String SUBAGENT = "subagent";
  public static final String TODOS = "todos";
  public static final String USAGE = "usage";
  public static final String REFERENCES = "references";
  public static final String QUEUED = "queued";
  public static final String QUEUED_READ = "queued-read";
  public static final String QUESTION = "question";
  public static final String ERROR = "error";
  public static final String FINISHED = "finished";

  /** Built without a sequence number; {@link RunJournal#append} stamps one on. */
  public static RunEvent of(final String type, final Map<String, Object> data) {
    return new RunEvent(0, type, data);
  }

  RunEvent at(final long assigned) {
    return new RunEvent(assigned, type, data);
  }
}
