package me.kezhenxu94.springagent.core.logging;

import com.google.common.base.Strings;
import java.util.LinkedHashMap;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Which run a log line belongs to, in the MDC.
 *
 * <p>A run is not a thread: it is assembled on one, streamed on Reactor's, waited out on a virtual
 * thread of its own, and reported back on whichever thread a surface uses. So there are two halves
 * to tagging its log lines, and both are needed.
 *
 * <p>This class is the first half — the explicit one. Opening a scope sets the three keys and
 * closing it puts back exactly what was there before, so a run started from inside another run's
 * thread (a subagent, a queued message being answered) leaves that thread as it found it.
 *
 * <p>The second half is {@link MdcThreadLocalAccessor}: with it registered, Reactor captures
 * whatever this class has set at subscribe time and restores it around every signal it delivers, so
 * the operators, Spring AI's own logging inside the chain and the tool-calling loop all see it
 * without anything being passed to them. That is why {@link
 * me.kezhenxu94.springagent.core.agent.SpringAgent#fire} only has to hold a scope open across the
 * subscription rather than around each callback.
 *
 * <p>Nothing here fails a run. An MDC that is missing a key costs a log line some context; anything
 * thrown from here would cost the run itself.
 */
public final class RunMdc {

  /** The id {@code SpringAgent} keys a live run by, and the one everything else correlates on. */
  public static final String REQUEST_ID = "requestId";

  /** The conversation the run belongs to, so a whole thread can be read at once. */
  public static final String CONVERSATION_ID = "conversationId";

  /** Who the run is for. Written to the log, so a deployment shipping its logs is shipping this. */
  public static final String USER_ID = "userId";

  private RunMdc() {}

  /** The run's identity, for as long as the scope is held. */
  public static Scope of(final AgentRequest request) {
    return request == null
        ? new Scope(Map.of())
        : of(request.requestId(), request.conversationId(), request.userId());
  }

  /**
   * The same, for a tool call.
   *
   * <p>Only two of the three: the tool context carries the run's identity but not its conversation
   * — see {@code SpringAgent#toolContextFor}. A tool call made on the run's own thread already has
   * all three from the scope the run holds, and this only has to cover the calls that are not: a
   * tool that does its work on an executor of its own.
   */
  public static Scope of(final ToolContext toolContext) {
    return of(
        ToolContexts.get(toolContext, ToolContexts.REQUEST_ID),
        null,
        ToolContexts.get(toolContext, ToolContexts.USER_ID));
  }

  public static Scope of(final String requestId, final String conversationId, final String userId) {
    final var values = new LinkedHashMap<String, String>();
    put(values, REQUEST_ID, requestId);
    put(values, CONVERSATION_ID, conversationId);
    put(values, USER_ID, userId);
    return new Scope(values);
  }

  private static void put(final Map<String, String> values, final String key, final String value) {
    // A blank is not an identity, and writing one would replace whatever the surrounding scope
    // knew with nothing — a subagent request that names no user would blank out the parent's.
    if (!Strings.isNullOrEmpty(value)) {
      values.put(key, value);
    }
  }

  /** What was set, and what was there before it. Close it on the thread that opened it. */
  public static final class Scope implements AutoCloseable {

    private final Map<String, String> restore = new LinkedHashMap<>();

    private Scope(final Map<String, String> values) {
      values.forEach(
          (key, value) -> {
            // Null where the key was not set, which close() puts back by removing it.
            restore.put(key, MDC.get(key));
            MDC.put(key, value);
          });
    }

    @Override
    public void close() {
      restore.forEach(
          (key, previous) -> {
            if (previous == null) {
              MDC.remove(key);
            } else {
              MDC.put(key, previous);
            }
          });
    }
  }
}
