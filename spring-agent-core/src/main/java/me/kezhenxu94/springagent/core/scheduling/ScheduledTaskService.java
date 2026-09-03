package me.kezhenxu94.springagent.core.scheduling;

import com.google.common.base.Strings;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * What a scheduled task <em>is</em> and what happens when one fires. When it fires is {@link
 * ScheduledTaskSweeper}'s, and the two are separate on purpose: this class holds no timer and no
 * memory of the schedule, so nothing here has to be rebuilt after a restart and nothing here is
 * per-replica. The whole of the schedule is {@code ScheduledTask#nextFireAt} in the database.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledTaskService {

  final SpringAgent springAgent;
  final ScheduledTaskRepo scheduledTaskRepo;
  final SpringAgentProperties appConfiguration;

  /**
   * The tasks whose firing has not come back yet, so that {@link ScheduledTaskSweeper} does not
   * start a second one over the top of it.
   *
   * <p>A firing is given the task's own id as its request id — {@code FiringScheduledTaskTool}
   * resolves the task it belongs to that way, and {@link #unschedule} cancels by it — and {@code
   * SpringAgent} keeps its live runs in a map on that key. Two firings of one task at once would
   * therefore share an entry, and the first to finish would take the other's out, after which
   * cancelling the task would silently do nothing.
   *
   * <p>Held in memory, and that is correct rather than a shortcut: the only overlap this has to
   * prevent is two firings in one process, since across replicas the conditional write in {@code
   * ScheduledTaskRepo#claimNextFireAt} means only one of them ever gets the occurrence.
   */
  private final Set<String> firing = ConcurrentHashMap.newKeySet();

  /**
   * The one-off tasks a firing of their own has given a next time, so that {@link
   * TaskLifecycleListener} does not then mark them done. Entered by {@link #rearmFiringTask} and
   * taken out by whichever of the two reads it first.
   */
  final Set<String> rearmed = ConcurrentHashMap.newKeySet();

  /**
   * Aborts the task's run if it is currently firing. The schedule itself is dropped by the caller
   * writing a status the sweeper does not pick up — {@code CancelScheduledTask} saves {@code
   * CANCELLED} before calling this.
   *
   * <p>{@code nextFireAt} is deliberately left as it was, so a cancelled task still records when it
   * would have fired next.
   */
  public void unschedule(final String taskId) {
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
    schedule(task);
  }

  /**
   * Applies an edit to a task and puts it on whatever schedule it now has.
   *
   * <p>The one place a task's definition is changed, reached both by the agent's {@code
   * UpdateScheduledTask} and by a person editing one in the browser. The rules live in {@link
   * ScheduledTaskEdit}; what is here is the write, which is not the same for every edit.
   *
   * <p>A change to the prompt alone is a partial write and nothing else — see {@code
   * ScheduledTaskRepo#updateTaskText}. The sweeper owns {@code runCount} and {@code nextFireAt} and
   * is writing them from another thread, or another replica, while somebody is editing; rewriting
   * the whole row would put a stale next occurrence back and fire the task at a time it has already
   * passed. And nothing needs rescheduling, because a firing reads the stored prompt when it fires
   * rather than a copy taken when the task was written.
   *
   * <p>Anything else goes through {@link #schedule}, which writes the whole row, because the next
   * occurrence has to be worked out again from a schedule that may have just changed. That write
   * does race the sweeper, and it is the same race {@link #schedule} itself has always had — losing
   * a firing's count to an edit made in the same instant is the price of the edit being applied at
   * all.
   *
   * @throws IllegalArgumentException when the edit is not one this task can take, with a message
   *     meant to be read by whoever asked
   */
  public ScheduledTaskEdit.Result edit(final ScheduledTask task, final ScheduledTaskEdit edit) {
    final var result = edit.applyTo(task);
    if (edit.textOnly()) {
      scheduledTaskRepo.updateTaskText(task.id(), result.task().taskText());
      log.info("Scheduled task {} had its prompt rewritten", task.id());
    } else {
      // One write, not a save followed by schedule's own: schedule writes the whole row anyway, so
      // saving first would store the task twice and store it once without its new next occurrence.
      schedule(result.task());
    }
    return result;
  }

  /**
   * Works out when the task next fires and writes it down. Nothing is armed: the sweeper reads
   * {@code nextFireAt} out of the database, so a task is scheduled the moment that field is set, on
   * every replica at once and across a restart.
   *
   * <p>A plain {@code save} rather than the conditional write the sweeper uses, because the only
   * caller is the tool that has just created or just edited this row on this replica — there is no
   * other writer to race with, and refusing to overwrite would be refusing to apply the edit.
   */
  public void schedule(final ScheduledTask task) {
    // Named rather than left to fail somewhere downstream on a null key, whose
    // NullPointerException carries no message at all.
    Objects.requireNonNull(
        task.id(), "a scheduled task must be saved with an id before scheduling");
    final var now = Instant.now();
    final var next = nextFireAtFor(task, now);
    if (next != null && next.isBefore(now)) {
      log.warn(
          "Scheduled task {} is already due at {}, it fires on the next sweep", task.id(), next);
    }
    scheduledTaskRepo.save(task.toBuilder().nextFireAt(next).build());
    log.info(
        "Scheduled task {}: cron={}, scheduledAt={}, nextFireAt={}",
        task.id(),
        task.cronExpression(),
        task.scheduledAt(),
        next);
  }

  /**
   * When {@code task} is next due, or null when it is due at no time at all — which for a one-off
   * means it was never given a time, and for a cron means an expression with no further occurrence.
   *
   * <p>A cron occurrence is always computed <em>from the given instant</em>, never from the {@code
   * nextFireAt} it replaces, and that is what decides catch-up behaviour: a task that was due eight
   * hundred times while the process was down fires once when it comes back, not eight hundred
   * times. A periodic task is a standing instruction rather than a debt — replaying the backlog
   * would be eight hundred model calls at the worst possible moment, and for the tasks people
   * actually write ("summarise what has happened since the last check") eight hundred identical
   * answers. What the user gets instead, and did not before, is that the missed occurrence fires
   * promptly rather than being skipped in silence until the next scheduled moment.
   *
   * <p>Two things follow from computing it from the expression each time rather than adding an
   * interval to the last one. The schedule cannot drift. And a sweep that runs late cannot
   * double-fire: {@code next} of 09:00:20 for {@code 0 0 9 * * *} is tomorrow morning, not this
   * one.
   *
   * <p>{@code ZoneId.systemDefault()} is what {@code CronTrigger} used implicitly before this, so
   * nobody's "nine in the morning" moves. It does make explicit something that used to be hidden:
   * replicas sharing a database must agree on {@code TZ}, or each will keep advancing the schedule
   * to its own idea of the next occurrence.
   */
  static Instant nextFireAtFor(final ScheduledTask task, final Instant from) {
    if (task.cronExpression() != null) {
      final var next =
          CronExpression.parse(task.cronExpression())
              .next(ZonedDateTime.ofInstant(from, ZoneId.systemDefault()));
      return next == null ? null : next.toInstant();
    }
    // A one-off's occurrence is simply the time it was given, whether that is in the future or long
    // past. Deliberately not conditional on the run count: this is also the answer for a task that
    // has just fired and been given a new time by its own run — see #rearmFiringTask, where the
    // count is already one. That a one-off fires only once is enforced by
    // #nextFireAtAfterFiring returning nothing, which is the question actually being asked there.
    return task.scheduledAt();
  }

  /**
   * When {@code task} fires again after the firing that is starting now, or null when it does not.
   *
   * <p>Distinct from {@link #nextFireAtFor}, which answers a different question — when is this task
   * due — and the difference is the whole of what makes a one-off fire once. A one-off's due time
   * is the time it was given, so asking that here would hand it back the very occurrence being
   * consumed and it would be due again on the next sweep. Only this method knows that an occurrence
   * is being spent, which is why the distinction is a second method rather than a flag.
   */
  static Instant nextFireAtAfterFiring(final ScheduledTask task, final Instant firedAt) {
    return task.cronExpression() == null ? null : nextFireAtFor(task, firedAt);
  }

  /** Whether a firing of this task started here has not come back yet. See {@link #firing}. */
  boolean isFiring(final String taskId) {
    return firing.contains(taskId);
  }

  /**
   * Runs the task, having decided it is due.
   *
   * <p>The guards below repeat checks {@link ScheduledTaskSweeper} has already made, and that is
   * wanted rather than redundant. The sweeper's versions <em>retire</em> the task — they take it
   * out of the active set it reads — while these are the last look before a run is paid for,
   * covering whatever changed between the occurrence being won and this being reached.
   */
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
      log.info("Scheduled task {} no longer exists, not firing it", armed.id());
      return;
    }
    if (task.status() != ScheduledTask.Status.ACTIVE) {
      log.info("Scheduled task {} is {}, not firing it", task.id(), task.status());
      return;
    }
    if (task.expiresAt() != null && task.expiresAt().isBefore(java.time.Instant.now())) {
      log.info("Scheduled task {} has expired, cancelling", task.id());
      scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.CANCELLED);
      return;
    }
    final var runsSoFar = task.runCount() == null ? 0 : task.runCount();
    if (task.maxRuns() != null && runsSoFar >= task.maxRuns()) {
      log.info(
          "Scheduled task {} has had all {} of its runs, completing", task.id(), task.maxRuns());
      scheduledTaskRepo.updateStatus(task.id(), ScheduledTask.Status.COMPLETED);
      return;
    }
    // Counted before the run rather than after it, so a firing that never comes back — a crash, a
    // shutdown mid-run — still spends its turn. The alternative loses the bound entirely.
    scheduledTaskRepo.incrementRunCount(task.id());
    log.info("Firing scheduled task {}: {}", task.id(), task.taskText());

    // Marked before the run starts and cleared by the listener when it ends. See #firing.
    firing.add(task.id());

    // A firing carries the conversation of the thread the task was created in, so each run reads
    // back the ones before it — and the user's own messages in that thread, as the thread reads
    // back the firings.
    try {
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
    } catch (RuntimeException e) {
      // fire reports through listeners rather than throwing, so this is the unexpected path. Give
      // the mark back regardless: a task left marked as firing is one the sweeper never fires
      // again, for the life of the process.
      firing.remove(task.id());
      throw e;
    }
  }

  /**
   * Ends the task whose firing is asking for it, once what it was waiting for has happened.
   *
   * <p>Deliberately not {@link #unschedule}: a firing is given the task's own id as its request id,
   * so cancelling by that id would abort the very run that called this — the task would stop, and
   * the run explaining why would never finish.
   */
  public void stopFiringTask(final String taskId) {
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
      firing.remove(task.id());
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
      // Load-bearing beyond recording the outcome: a status other than ACTIVE is what takes a
      // finished one-off out of the set the sweeper reads. Without it the task would be examined on
      // every sweep for ever, its null nextFireAt the only thing keeping it from firing again.
      scheduledTaskRepo.updateStatus(task.id(), terminalStatus);
    }
  }
}
