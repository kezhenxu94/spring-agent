package me.kezhenxu94.springagent.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;

/**
 * Handed to {@link AgentResponseListener#onStart} so an integration can take part in a run it did
 * not initiate: stream its output somewhere, react to its todo list, or contribute entries to the
 * tool context. An integration that initiated the run has no need for this — it attaches the same
 * things directly to its {@link AgentRequest}.
 *
 * <p>Only valid for the duration of that call, since the run is assembled from it the moment every
 * listener has had its turn.
 */
@RequiredArgsConstructor
public final class AgentRunRegistry {

  @Getter private final AgentRequest request;

  private final List<AgentResponseListener> responseListeners = new ArrayList<>();
  private final List<TodoEventHandler> todoEventHandlers = new ArrayList<>();
  private final Map<String, Object> toolContext = new LinkedHashMap<>();

  @Getter private String abortReason;

  /**
   * Calls off the run, for a listener whose output is the only reason it would have been worth
   * making. The run reports {@link AgentOutcome#FAILED} to every listener without contacting the
   * model. A listener that merely cannot report on the run should log and attach nothing instead.
   */
  public void abort(final String reason) {
    this.abortReason = reason;
  }

  /** Registers a listener for the agent's streamed response. */
  public void addResponseListener(final AgentResponseListener listener) {
    responseListeners.add(listener);
  }

  /** Registers a handler for todo-list updates the agent makes. */
  public void addTodoEventHandler(final TodoEventHandler handler) {
    todoEventHandlers.add(handler);
  }

  /** Contributes an entry to the tool context the agent's tools will see. */
  public void addToolContext(final String key, final Object value) {
    toolContext.put(key, value);
  }

  List<AgentResponseListener> responseListeners() {
    return responseListeners;
  }

  List<TodoEventHandler> todoEventHandlers() {
    return todoEventHandlers;
  }

  Map<String, Object> toolContext() {
    return toolContext;
  }
}
