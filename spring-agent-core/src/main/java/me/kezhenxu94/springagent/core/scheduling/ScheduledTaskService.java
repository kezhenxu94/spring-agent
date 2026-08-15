package me.kezhenxu94.springagent.core.scheduling;

import com.google.common.base.Strings;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

  final SpringAgent springAgent;
  final ScheduledTaskRepo scheduledTaskRepo;
  final SpringAgentProperties appConfiguration;
  final ThreadPoolTaskScheduler taskScheduler;

  final ConcurrentMap<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    final var now = Instant.now();
    final var activeTasks = scheduledTaskRepo.findByStatus(ScheduledTask.Status.ACTIVE);
    log.info("Loading {} active scheduled tasks on startup", activeTasks.size());
    activeTasks.forEach(
        task -> {
          if (task.expiresAt() != null && task.expiresAt().isBefore(now)) {
            log.info("Scheduled task {} has expired, marking as CANCELLED", task.id());
            scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
          } else {
            schedule(task);
          }
        });
  }

  /** Drops the task's schedule and aborts its run if it is currently firing. */
  public void unschedule(final String taskId) {
    final var future = scheduledFutures.remove(taskId);
    if (future != null) {
      future.cancel(false);
    }
    springAgent.cancel(taskId);
  }

  public void schedule(final ScheduledTask task) {
    // Named rather than left to fail on the ConcurrentHashMap put below, whose NullPointerException
    // carries no message at all.
    Objects.requireNonNull(
        task.id(), "a scheduled task must be saved with an id before scheduling");
    final Runnable runnable = () -> fire(task);
    final ScheduledFuture<?> future;
    if (task.cronExpression() != null) {
      future = taskScheduler.schedule(runnable, new CronTrigger(task.cronExpression()));
    } else {
      final var fireAt = task.scheduledAt();
      if (fireAt.isBefore(java.time.Instant.now())) {
        log.warn(
            "Scheduled task {} has a past scheduledAt {}, firing immediately", task.id(), fireAt);
      }
      future = taskScheduler.schedule(runnable, fireAt);
    }
    scheduledFutures.put(task.id(), future);
    log.info(
        "Scheduled task {}: cron={}, scheduledAt={}",
        task.id(),
        task.cronExpression(),
        task.scheduledAt());
  }

  void fire(final ScheduledTask task) {
    if (!springAgent.accepting()) {
      log.info("Shutting down, skipping scheduled task fire: {}", task.id());
      return;
    }
    if (task.expiresAt() != null && task.expiresAt().isBefore(java.time.Instant.now())) {
      log.info("Scheduled task {} has expired, cancelling", task.id());
      scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
      unschedule(task.id());
      return;
    }
    log.info("Firing scheduled task {}: {}", task.id(), task.taskText());

    // Task firings do not accumulate conversation history across runs, which
    // AgentScenario
    // .SCHEDULED_TASK expresses; conversationId is still passed as it is required
    // as the
    // ToolSearchToolCallingAdvisor's tool-index cache key (autoconfigured, see
    // ToolSearchAdvisorAutoConfiguration).
    springAgent.fire(
        AgentRequest.builder()
            .requestId(task.id())
            .scenario(AgentScenario.SCHEDULED_TASK)
            .userId(task.userId())
            .chatId(task.chatId())
            .chatType(task.chatType())
            .conversationId(task.rootMessageId())
            .rootMessageId(task.rootMessageId())
            .replyMessageId(task.rootMessageId())
            .userMessage(spec -> spec.text(firingPrompt(task)))
            .listener(new TaskLifecycleListener(task, task.cronExpression() != null))
            .build());
  }

  /**
   * The configured template over the one variable a firing has to offer, the task's own prompt.
   * Kept off the happy path of a blown-up template: a task that cannot be phrased is still worth
   * running, so its own text goes to the model unwrapped.
   */
  private String firingPrompt(final ScheduledTask task) {
    final var template = appConfiguration.ai().scheduledTaskPrompt();
    try {
      return PromptTemplate.builder()
          .template(template)
          .variables(Map.of("taskText", Strings.nullToEmpty(task.taskText())))
          .build()
          .render();
    } catch (Exception e) {
      log.error(
          "Failed to render app.ai.scheduled-task-prompt for task {}, sending its text as-is",
          task.id(),
          e);
      return Strings.nullToEmpty(task.taskText());
    }
  }

  @RequiredArgsConstructor
  private final class TaskLifecycleListener implements AgentResponseListener {
    private final ScheduledTask task;
    private final boolean isCron;

    @Override
    public void onFinished(AgentOutcome outcome) {
      log.info("Scheduled task {} completed, outcome={}", task.id(), outcome);
      // A cron task keeps its schedule and its ACTIVE status whatever a single firing
      // did.
      if (isCron) {
        return;
      }
      final var terminalStatus =
          switch (outcome) {
            case COMPLETED -> ScheduledTask.Status.COMPLETED;
            case FAILED -> ScheduledTask.Status.FAILED;
            case CANCELLED -> ScheduledTask.Status.CANCELLED;
          };
      scheduledTaskRepo.updateStatus(task.id(), terminalStatus);
      scheduledFutures.remove(task.id());
    }
  }
}
