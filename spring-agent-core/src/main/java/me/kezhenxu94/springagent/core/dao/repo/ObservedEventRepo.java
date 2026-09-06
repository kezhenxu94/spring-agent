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

  /**
   * Forgets every observation recorded against one situation, when that situation is forgotten.
   *
   * <p>The one property this can be keyed by, {@code situationId} being the only indexed one here.
   * JPA and MongoDB derive it; Redis writes it out as a read followed by deletes, for the reason
   * {@code RedisMcpServerConfigRepo.deleteByOwnerIdAndName} does.
   *
   * <p>Nothing else deletes an observation. It is the situation's lifetime that decides, because
   * evidence outliving what it is evidence for is a row nothing can ever ask for again.
   */
  void deleteBySituationId(String situationId);
}
