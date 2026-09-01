package me.kezhenxu94.springagent.integration.websocket.run;

import com.google.common.base.Strings;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.websocket.config.WebProperties;
import me.kezhenxu94.springagent.integration.websocket.web.WebQuestionHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives every agent run somewhere the browser can watch it, whichever way the run was started.
 *
 * <p>A bean rather than something the controller attaches to the request it fires, for the reason
 * {@code CliRunListener} and {@code FeishuCardListener} are beans: a scheduled task the agent set
 * up earlier fires through {@code ScheduledTaskService}, and a subagent is started by a tool.
 * Neither goes through anything this module calls, and without this their work would happen
 * invisibly.
 *
 * <p>{@code chatType} is what identifies this surface. A run that did not come from here is left
 * alone, so an application that somehow carried another surface too would not have its runs
 * rendered twice. {@code ScheduledTaskService} carries the chat type through to a firing, which is
 * what makes a task created in the browser show up in the browser.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebRunListener implements AgentResponseListener {

  /** The {@code chatType} every request this module builds carries. */
  public static final String CHAT_TYPE = "web";

  private final RunJournals journals;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final WebProperties properties;
  private final JsonMapper om;

  @Override
  public void onStart(final AgentRunRegistry registry) {
    final var request = registry.request();

    // A subagent first: it is not a run of its own to this surface, it is a panel inside the run
    // that started it, and it has no chatType of the parent's to be recognised by.
    final var parent = journals.byRequestId(request.parentRequestId()).orElse(null);
    if (parent != null) {
      attachSubagent(registry, request, parent);
      return;
    }

    if (!CHAT_TYPE.equals(request.chatType())) {
      return;
    }

    // A background run says what it has to say by sending something itself; there is no browser
    // waiting on it and nothing to draw. Its failures still reach the log.
    if (request.background()) {
      return;
    }

    final var journal =
        journals.open(request.requestId(), request.conversationId(), request.userId());
    final var renderer = new WebRunRenderer(journal, null);
    registry.addResponseListener(renderer);
    registry.addTodoEventHandler(renderer);
    registry.addToolContext(WebRunRenderer.TOOL_CONTEXT_KEY.key(), renderer);

    // Registering a handler is what decides whether the agent is offered the ask tool at all. Only
    // for a run somebody is having a conversation with: a scheduled task fires whether or not
    // anyone has the page open, and an answer to it would have no turn to rejoin.
    if (request.scenario() == BuiltInScenarios.CHAT) {
      registry.addQuestionHandler(
          new WebQuestionHandler(request, journal, pendingQuestionRepo, om, questionTtl()));
    }

    log.info(
        "Attached the browser to run {} (conversation={}, scenario={})",
        request.requestId(),
        request.conversationId(),
        request.scenario());
  }

  private void attachSubagent(
      final AgentRunRegistry registry, final AgentRequest request, final RunJournal parent) {
    final var id = request.requestId();
    final var renderer = new WebRunRenderer(parent, id);
    // Announced through the parent's own renderer so the panel exists before the subagent speaks.
    renderer.onSubagentStarted(id, request.description(), request.brief());
    registry.addResponseListener(renderer);
    registry.addTodoEventHandler(renderer);
    registry.addToolContext(WebRunRenderer.TOOL_CONTEXT_KEY.key(), renderer);
    log.debug(
        "Attached subagent {} to the journal of run {}",
        id,
        Strings.nullToEmpty(parent.requestId()));
  }

  private Duration questionTtl() {
    return properties.question().ttl();
  }
}
