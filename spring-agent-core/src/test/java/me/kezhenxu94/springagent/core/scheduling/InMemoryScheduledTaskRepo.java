package me.kezhenxu94.springagent.core.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;

/**
 * A store the sweeper tests can drive, whose two conditional writes are actually conditional.
 *
 * <p>Synchronized rather than merely thread-safe per operation, because the point of those two
 * methods is that the check and the write are one step. A backend that got this wrong would let two
 * replicas fire the same occurrence, so a test double that got it wrong would be unable to notice.
 * The backends themselves are covered by {@code AbstractPersistenceBackendTest}.
 */
final class InMemoryScheduledTaskRepo implements ScheduledTaskRepo {

  private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();

  @Override
  public synchronized ScheduledTask save(final ScheduledTask task) {
    tasks.put(task.id(), task);
    return task;
  }

  @Override
  public Optional<ScheduledTask> findById(final String id) {
    return Optional.ofNullable(tasks.get(id));
  }

  @Override
  public List<ScheduledTask> findByStatus(final ScheduledTask.Status status) {
    return tasks.values().stream().filter(task -> task.status() == status).toList();
  }

  @Override
  public List<ScheduledTask> findByUserIdAndStatus(
      final String userId, final ScheduledTask.Status status) {
    return findByStatus(status).stream().filter(task -> userId.equals(task.userId())).toList();
  }

  @Override
  public synchronized void updateStatus(final String id, final ScheduledTask.Status status) {
    tasks.computeIfPresent(id, (key, task) -> task.toBuilder().status(status).build());
  }

  @Override
  public synchronized void updateTaskText(final String id, final String taskText) {
    tasks.computeIfPresent(id, (key, task) -> task.toBuilder().taskText(taskText).build());
  }

  @Override
  public synchronized void incrementRunCount(final String id) {
    tasks.computeIfPresent(
        id,
        (key, task) ->
            task.toBuilder().runCount(task.runCount() == null ? 1 : task.runCount() + 1).build());
  }

  @Override
  public synchronized boolean claimNextFireAt(
      final String id, final Instant expected, final Instant next) {
    final var task = tasks.get(id);
    if (task == null || !Objects.equals(task.nextFireAt(), expected)) {
      return false;
    }
    tasks.put(id, task.toBuilder().nextFireAt(next).build());
    return true;
  }

  @Override
  public synchronized boolean initNextFireAt(final String id, final Instant next) {
    return claimNextFireAt(id, null, next);
  }
}
