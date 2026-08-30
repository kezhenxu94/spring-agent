package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoChatSessionRepo
    extends ChatSessionRepo, MongoRepository<ChatSession, String> {}
