package me.kezhenxu94.springagent.core.scheduling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.springaicommunity.agent.tools.TodoWriteTool.TodoEventHandler;

/**
 * Published immediately before a scheduled task's agent run is assembled, so that integrations can
 * take part in the run: stream its output somewhere, react to its todo list, or contribute entries
 * to the tool context.
 *
 * <p>Listeners of this event <strong>must be synchronous</strong> — never {@code @Async}. The
 * publisher reads back everything attached here as soon as {@code publishEvent} returns, so an
 * asynchronous listener would silently contribute nothing.
 *
 * <p>Listeners are also expected to handle their own failures. Anything thrown here aborts the task
 * firing entirely, so an integration that cannot set up its output should log and attach nothing
 * rather than propagate.
 */
@RequiredArgsConstructor
public final class ScheduledTaskFiringEvent {

  @Getter private final ScheduledTask task;

  private final List<AgentResponseListener> responseListeners = new ArrayList<>();
  private final List<TodoEventHandler> todoEventHandlers = new ArrayList<>();
  private final Map<String, Object> toolContext = new LinkedHashMap<>();

  /** Registers a listener to receive the agent's streamed response for this firing. */
  public void addResponseListener(final AgentResponseListener listener) {
    responseListeners.add(listener);
  }

  /** Registers a handler to receive todo-list updates the agent makes during this firing. */
  public void addTodoEventHandler(final TodoEventHandler handler) {
    todoEventHandlers.add(handler);
  }

  /** Contributes an entry to the tool context the agent's tools will see for this firing. */
  public void addToolContext(final String key, final Object value) {
    toolContext.put(key, value);
  }

  List<AgentResponseListener> responseListeners() {
    return responseListeners;
  }

  /** All registered todo handlers as one handler, fanning each update out to every listener. */
  TodoEventHandler todoEventHandler() {
    return todos -> todoEventHandlers.forEach(handler -> handler.handle(todos));
  }

  Map<String, Object> toolContext() {
    return toolContext;
  }
}
