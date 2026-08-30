package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaChatSessionRepo extends ChatSessionRepo, JpaRepository<ChatSession, String> {}
