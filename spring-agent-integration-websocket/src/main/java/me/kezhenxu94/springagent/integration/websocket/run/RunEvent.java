package me.kezhenxu94.springagent.integration.websocket.run;

import java.util.Map;

/**
 * One thing that happened in a run, as the browser will read it.
 *
 * @param seq its position in the run's journal, and the cursor a reconnecting browser resumes from.
 *     Assigned by {@link RunJournal}, never by whoever built the event
 * @param type what the frontend switches on. Every value below except {@link #REPLAY} and {@link
 *     #GONE} is something a run did; those two are the transport's own and are never journalled
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

  /**
   * A tool the run wants the person's device to run: {@code callId}, {@code name} and {@code
   * input}, the arguments exactly as the model wrote them. Only a run whose message declared device
   * tools emits one, which no browser does, so the page has no handler for it and drops it — see
   * {@link DeviceTools} for the round trip a native app makes on reading it.
   */
  public static final String DEVICE_TOOL = "device-tool";

  /**
   * That a {@link #DEVICE_TOOL} call is over, and how: {@code callId} and an {@code outcome} of
   * {@code answered}, {@code error}, {@code timeout} or {@code cancelled}. Journalled so that an
   * app replaying the run knows which calls it must not try to claim any more.
   */
  public static final String DEVICE_TOOL_DONE = "device-tool-done";

  /**
   * Sent to a subscriber before its backlog, carrying the last sequence number that already existed
   * as {@code through}, so the page can tell replay from live. Not a thing that happened in the run
   * — see {@code RunStreamSubscriptions} for why a subscriber has to be told rather than guess.
   */
  public static final String REPLAY = "replay";

  /**
   * Sent instead of a stream, when there is no journal for what was asked for — evicted, or never
   * this caller's. Deliberately the same answer to both.
   */
  public static final String GONE = "gone";

  /** Built without a sequence number; {@link RunJournal#append} stamps one on. */
  public static RunEvent of(final String type, final Map<String, Object> data) {
    return new RunEvent(0, type, data);
  }

  RunEvent at(final long assigned) {
    return new RunEvent(assigned, type, data);
  }
}
