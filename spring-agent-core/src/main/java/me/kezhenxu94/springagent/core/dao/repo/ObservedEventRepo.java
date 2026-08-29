package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared — see {@link ScheduledTaskRepo}.
 */
public interface ObservedEventRepo {

  ObservedEvent save(ObservedEvent event);

  /**
   * Every observation recorded against one situation, in no particular order.
   *
   * <p>Unordered and unlimited on purpose. Sorting and paging are what this codebase's {@code dao}
   * contracts do not have — no {@code Sort}, no {@code Pageable} anywhere — because a derived query
   * carrying them is not something the Redis backend can serve, and a contract only two of three
   * backends satisfy is not a contract. The caller takes the most recent few in memory, which is
   * affordable precisely because {@code app.events.max-events-per-situation} bounds what is stored.
   */
  List<ObservedEvent> findBySituationId(String situationId);
}
