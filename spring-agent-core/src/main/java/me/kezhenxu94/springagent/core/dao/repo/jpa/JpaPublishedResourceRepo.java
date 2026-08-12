package me.kezhenxu94.springagent.core.dao.repo.jpa;

import me.kezhenxu94.springagent.core.dao.models.PublishedResource;
import me.kezhenxu94.springagent.core.dao.repo.PublishedResourceRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA implementation, registered only when {@code app.persistence.type} is {@code jdbc}. */
public interface JpaPublishedResourceRepo
    extends PublishedResourceRepo, JpaRepository<PublishedResource, String> {}
