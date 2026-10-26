package me.kezhenxu94.springagent.integration.telegram.handler;

import com.google.common.base.Strings;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.integration.telegram.TelegramConversation;
import me.kezhenxu94.springagent.integration.telegram.TelegramMessageId;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import me.kezhenxu94.springagent.integration.telegram.format.TelegramText;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a Telegram run a reply to stream into, whichever way the run was started — {@code
 * SlackMessageListener}'s counterpart, covering the same three cases the same way: a run gets a
 * reply of its own, a subagent gets a panel in its parent's, and a background run gets nothing but
 * a word if it fails.
 *
 * <p><b>Which runs are Telegram's is read off the conversation key</b>, which is the {@code
 * rootMessageId} every run this module starts carries and every scheduled task started from one
 * fires back with — see {@link TelegramConversation}. A run whose key is not one of these is left
 * alone, which is what makes this listener safe to have claim every run as a bean.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMessageListener implements AgentResponseListener {

  private final TelegramBotApi telegram;
  private final JsonMapper om;
  private final SpringAgentProperties appConfiguration;
  private final TelegramProperties properties;
  private final TelegramMessages messages;
  private final TelegramMessageReactions reactions;
  private final TelegramQuestionForm questionForm;
  private final PendingQuestionRepo pendingQuestionRepo;
  private final ScheduledExecutorService telegramMessageFlushes;

  /** Where the reply's writes are made — see {@code SlackMessageListener} for the qualifier. */
  @Qualifier("telegramMessageWrites")
  private final ExecutorService telegramMessageWrites;

  // Not final: @Value on a field is an injection point AOT assigns directly.
  @Value("${app.ai.tools.ask-user-question.ttl:PT24H}")
  Duration questionTtl;

  /** A run reachable from its stop button, and the user it is for. */
  public record StoppableRun(String runId, String userId) {}

  private final ConcurrentMap<String, StoppableRun> runs = new ConcurrentHashMap<>();

  private final ConcurrentMap<String, TelegramMessageUpdater> updatersByRun =
      new ConcurrentHashMap<>();

  /** The run named by {@code runId}, or {@code null} if it has ended. */
  public StoppableRun runFor(final String runId) {
    return runs.get(runId);
  }

  @Override
  public void onStart(final AgentRunRegistry registry) {
    final var request = registry.request();

    final var parent =
        Strings.isNullOrEmpty(request.parentRequestId())
            ? null
            : updatersByRun.get(request.parentRequestId());
    if (parent != null) {
      final var updater =
          TelegramMessageUpdater.forSubagent(parent, request.requestId(), request.description());
      registry.addResponseListener(updater);
      registry.addToolContext(TelegramMessageUpdater.TOOL_CONTEXT_KEY.key(), updater);
      return;
    }

    final var conversation = TelegramConversation.parse(request.rootMessageId()).orElse(null);
    final var chatId = request.chatId();
    if (conversation == null || Strings.isNullOrEmpty(chatId)) {
      return;
    }
    final var runId = request.requestId();
    // A message id where the run answers a message; a scheduled task fires with the conversation
    // key here instead, and is sent to the chat without replying to anything.
    final var replyTo = TelegramMessageId.messageIdOrNull(request.replyMessageId());

    if (request.background()) {
      registry.addResponseListener(new BackgroundRun(chatId, conversation.threadId(), replyTo));
      return;
    }

    try {
      final var placeholder = TelegramText.plain(messages.get("message-starting"));
      final var posted =
          telegram.sendMessage(chatId, conversation.threadId(), placeholder, replyTo, null);
      log.info("Reply posted as {} in {} for run {}", posted.messageId(), chatId, runId);

      final var message =
          new TelegramMessage(
              telegram,
              chatId,
              conversation.threadId(),
              replyTo,
              posted.messageId(),
              placeholder,
              properties.streamInterval(),
              telegramMessageFlushes,
              telegramMessageWrites);
      final var updater =
          TelegramMessageUpdater.forRun(
              message,
              om,
              appConfiguration.ai().modelPricing(),
              messages,
              reactions,
              questionForm,
              runId);
      // The stop button goes on before anything is streamed: a run nobody can stop while it
      // thinks is a run that cannot be stopped when it matters.
      updater.begin();

      registry.addResponseListener(updater);
      registry.addTodoEventHandler(updater);
      registry.addToolContext(TelegramMessageUpdater.TOOL_CONTEXT_KEY.key(), updater);

      runs.put(runId, new StoppableRun(runId, request.userId()));
      updatersByRun.put(runId, updater);

      if (request.scenario().interactive()) {
        registry.addQuestionHandler(
            new TelegramQuestionHandler(
                request, updater, message, pendingQuestionRepo, om, questionTtl));
      }

      registry.addResponseListener(new RunRegistration(runId));
    } catch (Exception e) {
      log.error("Could not give run {} a Telegram reply to stream into", runId, e);
      if (request.scenario().interactive()) {
        // Somebody is waiting on this and nowhere will show them the answer.
        registry.abort("failed to post a Telegram reply");
      }
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

  /** A run nobody is watching, which reports nothing but a failure. */
  @RequiredArgsConstructor
  private final class BackgroundRun implements AgentResponseListener {
    private final String chatId;
    private final Integer threadId;
    private final Integer replyTo;

    private volatile Throwable failure;

    @Override
    public void onError(final Throwable error) {
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
        telegram.sendMessage(
            chatId,
            threadId,
            TelegramText.plain(messages.get("background-run-failed", reason)),
            replyTo,
            null);
      } catch (Exception e) {
        log.warn("Could not report a failed background run in {}", chatId, e);
      }
    }
  }
}
