package me.kezhenxu94.springagent.persistence.redis.repo;

import java.time.Duration;
import java.time.Instant;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.springframework.data.redis.core.PartialUpdate;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The {@code Impl} suffix is not decoration: Spring Data composes a repository from the fragment
 * interfaces it extends by looking for exactly {@code <FragmentInterface>Impl}, so renaming this
 * class detaches it and {@code updateStatus} starts failing as an underivable query.
 *
 * <p>A {@link PartialUpdate} rather than a read-modify-write, because that is what the contract
 * asks for: the callers hold a stale copy of the task and must not write the rest of its fields
 * back over a concurrent update. It also keeps the {@code status} secondary index correct — {@code
 * RedisKeyValueAdapter.update} re-writes the index entries for the properties the update names,
 * which is the half that a hand-rolled {@code HSET} would silently skip and that {@code
 * findByStatus} depends on.
 */
public class ScheduledTaskPartialUpdateImpl implements ScheduledTaskPartialUpdate {

  /**
   * How long the marker that settles one occurrence is kept.
   *
   * <p>It only has to outlive the moment between winning the occurrence and writing the new {@code
   * nextFireAt}, plus however stale a losing replica's copy of the task can be — at most one sweep
   * interval, which is seconds. Ten minutes is that with two orders of magnitude of headroom, and
   * being bounded at all is the entire reason this is not {@code ProcessedMessageRepo.claim}: that
   * one never expires, by design, so a task firing every five minutes would leave a hundred
   * thousand permanent keys a year in memory.
   */
  private static final Duration OCCURRENCE_MARKER_TTL = Duration.ofMinutes(10);

  private final RedisKeyValueTemplate template;

  /**
   * For the one operation this fragment needs that {@link RedisKeyValueTemplate} cannot express: an
   * atomic conditional write. {@code SET NX} is the only such primitive reachable here.
   */
  private final StringRedisTemplate strings;

  public ScheduledTaskPartialUpdateImpl(
      final RedisKeyValueTemplate template, final StringRedisTemplate strings) {
    this.template = template;
    this.strings = strings;
  }

  @Override
  public void updateStatus(final String id, final ScheduledTask.Status status) {
    template.update(PartialUpdate.newPartialUpdate(id, ScheduledTask.class).set("status", status));
  }

  @Override
  public void updateTaskText(final String id, final String taskText) {
    template.update(
        PartialUpdate.newPartialUpdate(id, ScheduledTask.class).set("taskText", taskText));
  }

  /**
   * Redis has no {@code $inc} reachable through this API, so the count is read back before it is
   * written. Safe without a transaction because a task's run count still has exactly one writer:
   * only the replica that won this occurrence through {@link #claimNextFireAt} goes on to fire it,
   * and nothing else increments. The write is partial besides, so it cannot undo an edit made to
   * the rest of the task meanwhile.
   */
  @Override
  public void incrementRunCount(final String id) {
    final var current = template.findById(id, ScheduledTask.class).orElse(null);
    if (current == null) {
      return;
    }
    final var next = current.runCount() == null ? 1 : current.runCount() + 1;
    template.update(PartialUpdate.newPartialUpdate(id, ScheduledTask.class).set("runCount", next));
  }

  @Override
  public boolean claimNextFireAt(final String id, final Instant expected, final Instant next) {
    return claim(markerKey(id, expected), id, next);
  }

  @Override
  public boolean initNextFireAt(final String id, final Instant next) {
    return claim(markerKey(id, null), id, next);
  }

  /**
   * The conditional write, in the only shape Redis offers here.
   *
   * <p>Spring Data Redis has no compare-and-set through {@link RedisKeyValueTemplate}, and a read
   * of {@code nextFireAt} followed by a write of it would let two replicas both through — which is
   * the one thing this exists to prevent. So the decision is made by {@code SET NX} on a key naming
   * the occurrence, which both replicas derive identically from the value they read, and only the
   * winner goes on to move the task.
   */
  private boolean claim(final String marker, final String id, final Instant next) {
    if (!Boolean.TRUE.equals(
        strings
            .opsForValue()
            .setIfAbsent(marker, Instant.now().toString(), OCCURRENCE_MARKER_TTL))) {
      return false;
    }
    // del rather than set(..., null): a Redis hash cannot hold a null, and a PartialUpdate given
    // one writes nothing at all rather than removing the field — which would leave the task looking
    // due for ever at the occurrence it has just fired.
    template.update(
        next == null
            ? PartialUpdate.newPartialUpdate(id, ScheduledTask.class).del("nextFireAt")
            : PartialUpdate.newPartialUpdate(id, ScheduledTask.class).set("nextFireAt", next));
    return true;
  }

  private static String markerKey(final String id, final Instant expected) {
    return ScheduledTask.COLLECTION_NAME
        + ":occurrence:"
        + id
        + "@"
        + (expected == null ? "none" : expected.toEpochMilli());
  }
}
