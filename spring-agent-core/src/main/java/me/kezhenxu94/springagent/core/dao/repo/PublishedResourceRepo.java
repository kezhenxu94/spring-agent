package me.kezhenxu94.springagent.core.dao.repo;

import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.PublishedResource;

/** Backend-neutral contract; see {@link ScheduledTaskRepo}. */
public interface PublishedResourceRepo {

  PublishedResource save(PublishedResource resource);

  Optional<PublishedResource> findById(String id);

  void deleteById(String id);
}
