package me.kezhenxu94.springagent.core.dao.repo;

import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserPreference;

/**
 * Backend-neutral contract; see {@link ScheduledTaskRepo}.
 *
 * <p>By id alone, in all three directions: a preference belongs to the person a run is for, and
 * nothing ever asks who else has set one. {@code deleteById} is how somebody puts everything back
 * the way it was, which is worth having as one call rather than as a field-by-field unset that can
 * be done half way.
 */
public interface UserPreferenceRepo {

  UserPreference save(UserPreference preference);

  Optional<UserPreference> findById(String id);

  void deleteById(String id);
}
