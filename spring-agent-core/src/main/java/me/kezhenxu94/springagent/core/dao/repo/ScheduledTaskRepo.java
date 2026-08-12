package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared — deliberately narrower than a {@code
 * CrudRepository}, so that adding a backend means implementing a known, small surface.
 *
 * <p>Spring Data generates the implementation from the sub-interface of the selected backend, so
 * the method names here have to remain valid derived queries on both sides.
 */
public interface ScheduledTaskRepo {

  ScheduledTask save(ScheduledTask task);

  Optional<ScheduledTask> findById(String id);

  List<ScheduledTask> findByStatus(ScheduledTask.Status status);

  List<ScheduledTask> findByUserIdAndStatus(String userId, ScheduledTask.Status status);

  /**
   * Sets the status of one task without loading it, which is what the callers actually want — they
   * hold a stale copy and must not write the rest of its fields back over a concurrent update.
   */
  void updateStatus(String id, ScheduledTask.Status status);
}
