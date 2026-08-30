package me.kezhenxu94.springagent.appcli;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import org.springframework.stereotype.Component;

/**
 * Gives every agent run in this process somewhere to show itself, whichever way it was started.
 *
 * <p>A bean rather than something {@link CliShellRunner} does when it fires a request, for the same
 * reason {@code FeishuCardListener} is one: a scheduled task the agent set up earlier fires through
 * {@code ScheduledTaskService}, not through anything the command line calls, and without this its
 * output would go nowhere while the user watched an idle prompt.
 *
 * <p>Every run, a background one included, which is where this parts company with {@code
 * FeishuCardListener}. A background run is one that says what it has to say by sending a message
 * itself, and the command line has nothing to send one with: honouring the flag here would leave
 * such a run with no way at all to be seen. The terminal is the log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CliRunListener implements AgentResponseListener {

  private final CliConsole console;
  private final CliQuestionHandler questionHandler;
  private final CliMessages messages;

  @Override
  public void onStart(final AgentRunRegistry registry) {
    // A subagent is not a turn of its own: it is work the run that started it is waiting on, and
    // its
    // answer reaches the terminal as that run reads it. Rendering it here as well would interleave
    // two answers in one gutter, and — with several subagents going at once — several.
    if (registry.request().scenario() == BuiltInScenarios.SUBAGENT) {
      return;
    }
    final var renderer = new CliRenderer(console, messages);
    registry.addResponseListener(renderer);
    registry.addTodoEventHandler(renderer);
    registry.addToolContext(CliRenderer.TOOL_CONTEXT_KEY.key(), renderer);

    // Registering a handler is what decides whether the agent is offered the tool at all. Only for
    // a run somebody is watching: a scheduled task fires whether or not the user is at the
    // terminal,
    // and its prompt already tells the model there is nobody to ask.
    if (registry.request().scenario() == BuiltInScenarios.CHAT) {
      registry.addQuestionHandler(questionHandler);
    }

    log.info(
        "Attached the terminal to run {} (scenario={})",
        registry.request().requestId(),
        registry.request().scenario());
  }
}
