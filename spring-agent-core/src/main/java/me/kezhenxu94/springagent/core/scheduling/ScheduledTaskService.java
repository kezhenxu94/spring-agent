package me.kezhenxu94.springagent.core.scheduling;

import com.google.common.base.Strings;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentOutcome;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
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

  /**
   * The one-off tasks a firing of their own has given a next time, so that {@link
   * TaskLifecycleListener} does not then mark them done. Entered by {@link #rearmFiringTask} and
   * taken out by whichever of the two reads it first.
   */
  final Set<String> rearmed = ConcurrentHashMap.newKeySet();

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
    dropSchedule(taskId);
    springAgent.cancel(taskId);
  }

  /**
   * Puts a changed task on its new schedule, in place of whatever it was on before.
   *
   * <p>Deliberately not {@link #unschedule} followed by {@link #schedule}: a firing already under
   * way was started by the definition as it stood, and aborting it half-done is not what editing
   * the task asks for. What changes is the next firing.
   */
  public void reschedule(final ScheduledTask task) {
    dropSchedule(task.id());
    schedule(task);
  }

  /**
   * Forgets the task's timer without touching a run. Cancelled without interrupting, since the
   * future's own thread is the one a firing runs on.
   */
  private void dropSchedule(final String taskId) {
    final var future = scheduledFutures.remove(taskId);
    if (future != null) {
      future.cancel(false);
    }
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

  void fire(final ScheduledTask armed) {
    if (!springAgent.accepting()) {
      log.info("Shutting down, skipping scheduled task fire: {}", armed.id());
      return;
    }
    // Read the task back rather than firing the copy this timer closed over. The count of firings
    // is written by the firing before this one, and the status can have been set by anything since
    // the timer was armed — a firing of a task somebody has already cancelled is the case that
    // costs a run for nothing.
    final var task = scheduledTaskRepo.findById(armed.id()).orElse(null);
    if (task == null) {
      log.info("Scheduled task {} no longer exists, dropping its schedule", armed.id());
      dropSchedule(armed.id());
      return;
    }
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      log.info("Scheduled task {} is {}, dropping its schedule", task.id(), task.status());
      dropSchedule(task.id());
      return;
    }
    if (task.expiresAt() != null && task.expiresAt().isBefore(java.time.Instant.now())) {
      log.info("Scheduled task {} has expired, cancelling", task.id());
      scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
      unschedule(task.id());
      return;
    }
    final var runsSoFar = task.runCount() == null ? 0 : task.runCount();
    if (task.maxRuns() != null && runsSoFar >= task.maxRuns()) {
      log.info(
          "Scheduled task {} has had all {} of its runs, completing", task.id(), task.maxRuns());
      scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.COMPLETED);
      // dropSchedule rather than unschedule: unschedule cancels the run whose id is this task's,
      // and nothing here is running.
      dropSchedule(task.id());
      return;
    }
    // Counted before the run rather than after it, so a firing that never comes back — a crash, a
    // shutdown mid-run — still spends its turn. The alternative loses the bound entirely.
    scheduledTaskRepo.incrementRunCount(task.id());
    log.info("Firing scheduled task {}: {}", task.id(), task.taskText());

    // A firing carries the conversation of the thread the task was created in, so each run reads
    // back the ones before it — and the user's own messages in that thread, as the thread reads
    // back the firings.
    springAgent.fire(
        AgentRequest.builder()
            .requestId(task.id())
            .scenario(BuiltInScenarios.SCHEDULED_TASK)
            .userId(task.userId())
            .chatId(task.chatId())
            .chatType(task.chatType())
            // The scopes the task was created in, so a firing reaches the same group and tenant
            // homes and knowledge the conversation that created it could.
            .groupId(task.groupId())
            .tenantId(task.tenantId())
            .conversationId(task.rootMessageId())
            .rootMessageId(task.rootMessageId())
            .replyMessageId(task.rootMessageId())
            .background(Boolean.TRUE.equals(task.background()))
            .userMessage(spec -> spec.text(firingPrompt(task)))
            .listener(new TaskLifecycleListener(task, task.cronExpression() != null))
            .build());
  }

  /**
   * Ends the task whose firing is asking for it, once what it was waiting for has happened.
   *
   * <p>Deliberately not {@link #unschedule}: a firing is given the task's own id as its request id,
   * so cancelling by that id would abort the very run that called this — the task would stop, and
   * the run explaining why would never finish.
   */
  public void stopFiringTask(final String taskId) {
    dropSchedule(taskId);
    rearmed.remove(taskId);
    scheduledTaskRepo.updateStatus(taskId, ScheduledTask.Status.COMPLETED);
    log.info("Scheduled task {} stopped itself", taskId);
  }

  /**
   * Gives a one-off task a next firing, in place of the one that is running now. The task is the
   * same row it always was, which is what keeps a chain of follow-ups from becoming a pile of
   * tasks: however many times a firing arranges the next one, there is still one task.
   */
  public void rearmFiringTask(final ScheduledTask saved) {
    // Entered before the schedule so that a run finishing while this method is still working still
    // finds the mark. TaskLifecycleListener would otherwise write the task off as done.
    rearmed.add(saved.id());
    dropSchedule(saved.id());
    schedule(saved);
    log.info("Scheduled task {} re-armed itself for {}", saved.id(), saved.scheduledAt());
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
      // A firing that gave this task a next time has already said what happens to it. Marking it
      // done here would take away the schedule the run just asked for.
      if (rearmed.remove(task.id())) {
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
