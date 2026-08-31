package me.kezhenxu94.springagent.integration.slack.handler;

import com.google.common.base.Strings;
import com.slack.api.methods.MethodsClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a Slack-originated agent run a message to stream into, whichever way the run was started:
 * it posts a reply into the thread the run is answering and attaches a {@link SlackMessageUpdater}
 * to the run.
 *
 * <p>A bean rather than something the message handler or the scheduler calls, so neither has to
 * know that streaming replies exist and core does not have to know that Slack does. Being a bean is
 * also what lets this cover runs it did not start — a scheduled task firing, a triage run.
 *
 * <p>A subagent is the other way a run reaches a message: it did not come from a message, so there
 * is nothing to reply to, and it belongs to a run that already has one. It gets a panel in that
 * message and an updater confined to it, which is how a reader sees the work behind an answer
 * without a second message and a second stop button appearing for something they never started.
 *
 * <p>A background run is the exception and gets no message at all: it is unattended by definition,
 * so there is nobody the reply would be streaming to. Such a run says whatever it has to say by
 * sending a message itself, and a reply announcing the run is a second message on top of that — or,
 * for a task that decided it had nothing to say, the only one, which is exactly the message its
 * author did not want. The one thing still reported here is a failure, since a run that fell over
 * is the one thing it cannot report for itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackMessageListener implements AgentResponseListener {

  private final MethodsClient slack;
  private final JsonMapper om;
  private final SpringAgentProperties appConfiguration;
  private final SlackMessages messages;
  private final SlackMessageReactions reactions;
  private final SlackQuestionForm questionForm;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final ScheduledExecutorService slackMessageFlushes;

  /**
   * Where the message's writes are made, which is not the thread the run streams on.
   *
   * <p>Named, because a {@code ScheduledExecutorService} is an {@code ExecutorService} too: by type
   * alone this and {@code slackMessageFlushes} are both candidates, and the qualifier is what keeps
   * the calls off the clock's two threads. Copied onto the constructor parameter by Lombok — see
   * {@code lombok.config}.
   */
  @Qualifier("slackMessageWrites")
  private final ExecutorService slackMessageWrites;

  // Not final: @Value on a field is an injection point in its own right, and AOT generates a plain
  // field assignment for it, which cannot target a final field the way reflective injection can.
  @Value("${app.slack.stream-interval}")
  Duration streamInterval;

  @Value("${app.slack.stream-characters}")
  int streamCharacters;

  @Value("${app.ai.tools.ask-user-question.ttl:PT24H}")
  Duration questionTtl;

  /**
   * A run reachable from the message it is streaming into, and the user it was started for: the
   * stop button is on a message, and a message in a channel is in front of everyone.
   */
  public record StoppableRun(String runId, String userId) {}

  /**
   * Which run each reply belongs to. The stop button carries the run id itself, so this is only
   * consulted to find out who may press it — and cancelling is in-memory anyway, so this is exactly
   * as durable as the thing it feeds.
   */
  private final ConcurrentMap<String, StoppableRun> runs = new ConcurrentHashMap<>();

  /**
   * What each run is streaming into, which is what a subagent of it is attached to. Keyed by the
   * run, since a subagent knows only which run started it.
   */
  private final ConcurrentMap<String, SlackMessageUpdater> updatersByRun =
      new ConcurrentHashMap<>();

  /** The run named by {@code runId}, or {@code null} if it has ended. */
  public StoppableRun runFor(final String runId) {
    return runs.get(runId);
  }

  @Override
  public void onStart(final AgentRunRegistry registry) {
    final var request = registry.request();

    // A subagent is work some other run asked for, and it has no message of its own to reply to. It
    // gets a panel in that run's message instead — before the reply check below, which a run with
    // nothing to reply to would otherwise fall out of.
    final var parent =
        Strings.isNullOrEmpty(request.parentRequestId())
            ? null
            : updatersByRun.get(request.parentRequestId());
    if (parent != null) {
      final var updater =
          SlackMessageUpdater.forSubagent(parent, request.requestId(), request.description());
      registry.addResponseListener(updater);
      registry.addToolContext(SlackMessageUpdater.TOOL_CONTEXT_KEY.key(), updater);
      return;
    }

    final var channelId = request.chatId();
    final var threadTs =
        Strings.isNullOrEmpty(request.rootMessageId())
            ? request.replyMessageId()
            : request.rootMessageId();
    // Nothing to reply to means the run did not come from Slack at all.
    if (Strings.isNullOrEmpty(channelId) || Strings.isNullOrEmpty(threadTs)) {
      return;
    }

    final var runId = request.requestId();

    if (request.background()) {
      // No message, and so no stop button either: a background run is not on screen to be stopped.
      registry.addResponseListener(new BackgroundRun(channelId, threadTs));
      return;
    }

    try {
      final var ts = post(channelId, threadTs);
      if (ts == null) {
        abortOrCarryOn(registry, "failed to post a Slack reply");
        return;
      }
      log.info("Reply posted as {} in {} for run {}", ts, channelId, runId);

      final var message =
          new SlackMessage(
              slack,
              channelId,
              ts,
              threadTs,
              streamInterval,
              streamCharacters,
              slackMessageFlushes,
              slackMessageWrites);
      message.continuation(full -> post(channelId, threadTs));

      final var updater =
          SlackMessageUpdater.forRun(
              message,
              om,
              appConfiguration.ai().modelPricing(),
              messages,
              reactions,
              questionForm,
              runId,
              request.userId());
      // The stop button has to be on the message before anything can be streamed into it: a run
      // that cannot be stopped until it has said something is one nobody can stop while it thinks.
      updater.begin();

      registry.addResponseListener(updater);
      registry.addTodoEventHandler(updater);
      registry.addToolContext(SlackMessageUpdater.TOOL_CONTEXT_KEY.key(), updater);

      runs.put(runId, new StoppableRun(runId, request.userId()));
      updatersByRun.put(runId, updater);

      // Only a chat run, and registering this is what decides whether the agent may ask at all. A
      // scheduled task has no conversation memory, so an answer arriving later would have nothing
      // to rejoin — its prompt already tells the model there is nobody to ask.
      if (request.scenario() == BuiltInScenarios.CHAT) {
        registry.addQuestionHandler(
            new SlackQuestionHandler(
                request, updater, message, pendingQuestionRepo, om, questionTtl));
      }

      registry.addResponseListener(new RunRegistration(runId));
    } catch (Exception e) {
      log.error("Could not give run {} a Slack reply to stream into", runId, e);
      abortOrCarryOn(registry, "failed to post a Slack reply");
    }
  }

  /**
   * Posts an empty reply into {@code threadTs} and returns its timestamp, or null.
   *
   * <p>Also what a rollover calls: a continuation is the same thing in the same thread, which is
   * why the two share this rather than each having their own.
   */
  private String post(final String channelId, final String threadTs) {
    try {
      final var response =
          slack.chatPostMessage(
              r ->
                  r.channel(channelId)
                      .threadTs(threadTs)
                      .text(messages.get("message-conversation-hint"))
                      .blocks(List.of()));
      if (!response.isOk()) {
        log.warn("Slack refused a reply in {}: {}", channelId, response.getError());
        return null;
      }
      return response.getTs();
    } catch (Exception e) {
      log.warn("Could not post a reply in {}", channelId, e);
      return null;
    }
  }

  /**
   * A chat message whose reply never appeared has nowhere to put its answer, so the run is
   * pointless; a scheduled task does its work regardless and goes ahead unreported.
   */
  private static void abortOrCarryOn(final AgentRunRegistry registry, final String reason) {
    if (registry.request().scenario() == BuiltInScenarios.CHAT) {
      registry.abort(reason);
    }
  }

  /** Forgets the run once it has ended, so neither map grows for the life of the process. */
  @RequiredArgsConstructor
  private final class RunRegistration implements AgentResponseListener {
    private final String runId;

    @Override
    public void onFinished(final AgentOutcome outcome) {
      runs.remove(runId);
      updatersByRun.remove(runId);
    }
  }

  /**
   * A run nobody is watching. It reports nothing but a failure, which is the one thing it cannot
   * report for itself.
   */
  @RequiredArgsConstructor
  private final class BackgroundRun implements AgentResponseListener {
    private final String channelId;
    private final String threadTs;

    private volatile Throwable failure;

    @Override
    public void onError(final Throwable error) {
      // Kept, because AgentOutcome says only that the run failed.
      failure = error;
    }

    @Override
    public void onFinished(final AgentOutcome outcome) {
      if (outcome != AgentOutcome.FAILED) {
        return;
      }
      final var reason =
          failure == null || Strings.isNullOrEmpty(failure.getMessage())
              ? String.valueOf(outcome)
              : failure.getMessage();
      try {
        slack.chatPostMessage(
            r ->
                r.channel(channelId)
                    .threadTs(threadTs)
                    .text(messages.get("background-run-failed", reason)));
      } catch (Exception e) {
        log.warn("Could not report a failed background run in {}", channelId, e);
      }
    }
  }
}
