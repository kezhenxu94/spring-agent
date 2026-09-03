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
 * alone, so an application carrying a chat surface as well does not have that surface's runs
 * rendered twice — which is the whole reason this module is publishable beside one. {@code
 * ScheduledTaskService} carries the chat type through to a firing, which is what makes a task
 * created in the browser show up in the browser.
 *
 * <p><b>Unless a deployment asks to follow them.</b> {@code app.web.follow-chat-runs} widens the
 * gate to the chat surface's own runs, so somebody who started a conversation in a chat can open it
 * here and watch the run as it happens instead of reading it once it is over. That is the other
 * half of a handoff, and it cannot be done from outside this process — a journal is held in memory.
 * It is off by default because it is not free: every chat run is then journaled for {@code
 * app.web.journal.retention} whether or not a browser ever asks for it. Following a run does not
 * take it over — the chat surface still renders its own card, a journal is only ever read, and both
 * question handlers put the form up so an answer can come back from whichever one the person is
 * looking at.
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

    if (!CHAT_TYPE.equals(request.chatType()) && !following(request.chatType())) {
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

  /**
   * Whether a run belonging to the chat surface beside this page is followed here too.
   *
   * <p>The chat types are the ones every chat integration in this codebase uses — Feishu and Slack
   * both describe a conversation as {@code p2p} or {@code group}/{@code channel} — rather than a
   * list a deployment configures. A surface that named something else would simply not be followed,
   * which is the safe way round: an unrecognised chat type is a run this module knows nothing about
   * and has no business opening a journal for.
   */
  private boolean following(final String chatType) {
    if (!properties.followChatRuns() || chatType == null) {
      return false;
    }
    return switch (chatType.toLowerCase(java.util.Locale.ROOT)) {
      case "p2p", "group", "channel" -> true;
      default -> false;
    };
  }

  private Duration questionTtl() {
    return properties.question().ttl();
  }
}
