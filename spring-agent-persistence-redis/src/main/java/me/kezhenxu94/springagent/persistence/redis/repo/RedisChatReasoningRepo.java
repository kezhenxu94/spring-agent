package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import org.springframework.data.repository.CrudRepository;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>{@code findByConversationId} derives because {@code conversationId} is {@code @Indexed}, which
 * on Redis is what makes a property filterable at all rather than merely faster to filter on.
 */
public interface RedisChatReasoningRepo
    extends ChatReasoningRepo, CrudRepository<ChatReasoning, String> {

  /**
   * Written out rather than derived, like {@code RedisObservedEventRepo.deleteBySituationId}:
   * {@code RedisQueryCreator} builds finders over an indexed property and nothing else, so there is
   * no delete to derive from a part tree here.
   *
   * <p>Bounded by the conversation: one row a round, and it is read only as the conversation is
   * being deleted.
   */
  @Override
  default void deleteByConversationId(final String conversationId) {
    findByConversationId(conversationId).forEach(reasoning -> deleteById(reasoning.id()));
  }
}
