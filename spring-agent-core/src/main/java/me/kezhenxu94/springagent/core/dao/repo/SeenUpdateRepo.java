package me.kezhenxu94.springagent.core.dao.repo;

import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.SeenUpdate;

/**
 * Backend-neutral contract; see {@link ScheduledTaskRepo}.
 *
 * <p>Read and written by id alone: a greeting is about the person in front of it, and nothing here
 * ever asks who else is behind. Deliberately no delete — forgetting what somebody read would greet
 * them a second time with what they have already seen.
 */
public interface SeenUpdateRepo {

  SeenUpdate save(SeenUpdate seenUpdate);

  Optional<SeenUpdate> findById(String id);
}
