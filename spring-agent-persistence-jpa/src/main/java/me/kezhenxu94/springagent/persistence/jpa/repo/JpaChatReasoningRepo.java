package me.kezhenxu94.springagent.persistence.jpa.repo;

import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;
import me.kezhenxu94.springagent.core.dao.repo.ChatReasoningRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** The JPA implementation, registered when this module is the persistence backend in play. */
public interface JpaChatReasoningRepo
    extends ChatReasoningRepo, JpaRepository<ChatReasoning, String> {

  // A derived delete is not transactional the way the inherited deleteById is, so without this it
  // throws TransactionRequiredException the first time a conversation is deleted. The same
  // override, for the same reason, as JpaObservedEventRepo.deleteBySituationId.
  @Override
  @Transactional
  void deleteByConversationId(String conversationId);
}
