package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>{@code findByConversationIdAndStatus} derives: both properties are indexed, so it is an
 * intersection of two Redis sets. {@code updateStatus} cannot — Spring Data Redis has no annotation
 * for a partial update the way JPA and MongoDB do — and comes from {@link
 * PendingQuestionStatusUpdate}.
 */
public interface RedisPendingQuestionRepo
    extends PendingQuestionRepo,
        PendingQuestionStatusUpdate,
        CrudRepository<PendingQuestion, String> {}
