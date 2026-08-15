package me.kezhenxu94.springagent.cli;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import org.springframework.stereotype.Component;

/**
 * Gives every agent run in this process somewhere to show itself, whichever way it was started.
 *
 * <p>A bean rather than something {@link CliShellRunner} does when it fires a request, for the same
 * reason {@code FeishuCardListener} is one: a scheduled task the agent set up earlier fires through
 * {@code ScheduledTaskService}, not through anything the command line calls, and without this its
 * output would go nowhere while the user watched an idle prompt.
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
    final var renderer = new CliRenderer(console, messages);
    registry.addResponseListener(renderer);
    registry.addTodoEventHandler(renderer);
    registry.addToolContext(CliRenderer.TOOL_CONTEXT_KEY.key(), renderer);

    // Registering a handler is what decides whether the agent is offered the tool at all. Only for
    // a run somebody is watching: a scheduled task fires whether or not the user is at the
    // terminal,
    // and its prompt already tells the model there is nobody to ask.
    if (registry.request().scenario() == AgentScenario.CHAT) {
      registry.addQuestionHandler(questionHandler);
    }

    log.info(
        "Attached the terminal to run {} (scenario={})",
        registry.request().requestId(),
        registry.request().scenario());
  }
}
