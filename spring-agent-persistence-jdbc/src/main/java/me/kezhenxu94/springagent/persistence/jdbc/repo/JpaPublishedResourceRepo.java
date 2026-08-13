package me.kezhenxu94.springagent.persistence.jdbc.repo;

import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaPublishedResourceRepo
    extends PublishedResourceRepo, JpaRepository<PublishedResource, String> {}
