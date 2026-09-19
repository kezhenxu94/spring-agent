package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoChatReasoningRepo
    extends ChatReasoningRepo, MongoRepository<ChatReasoning, String> {}
