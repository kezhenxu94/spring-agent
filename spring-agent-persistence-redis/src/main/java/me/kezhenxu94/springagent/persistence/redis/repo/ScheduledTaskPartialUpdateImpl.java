package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import org.springframework.data.redis.core.PartialUpdate;
import org.springframework.data.redis.core.RedisKeyValueTemplate;

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

  private final RedisKeyValueTemplate template;

  public ScheduledTaskPartialUpdateImpl(final RedisKeyValueTemplate template) {
    this.template = template;
  }

  @Override
  public void updateStatus(final String id, final ScheduledTask.Status status) {
    template.update(PartialUpdate.newPartialUpdate(id, ScheduledTask.class).set("status", status));
  }

  /**
   * Redis has no {@code $inc} reachable through this API, so the count is read back before it is
   * written. Safe without a transaction because a task's run count has exactly one writer — the
   * scheduler thread firing that task — and the write is still partial, so it cannot undo an edit
   * made to the rest of the task meanwhile.
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
}
