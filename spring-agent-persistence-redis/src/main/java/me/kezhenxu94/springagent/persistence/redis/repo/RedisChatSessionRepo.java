package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatSession;
import me.kezhenxu94.springagent.core.dao.repo.ChatSessionRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>{@code findByUserId} derives because {@code userId} is {@code @Indexed}, which on Redis is
 * what makes a property filterable at all rather than merely faster to filter on.
 */
public interface RedisChatSessionRepo
    extends ChatSessionRepo, CrudRepository<ChatSession, String> {}
