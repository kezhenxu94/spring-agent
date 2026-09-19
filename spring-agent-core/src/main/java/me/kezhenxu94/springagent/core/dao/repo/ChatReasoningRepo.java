package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatReasoning;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared, and the method names have to remain
 * valid derived queries on every backend — see {@link ScheduledTaskRepo}.
 */
public interface ChatReasoningRepo {

  ChatReasoning save(ChatReasoning reasoning);

  Optional<ChatReasoning> findById(String id);

  /**
   * Everything one conversation thought, in no particular order — the caller pairs the rows to the
   * turns it is drawing rather than reading them in sequence, so no backend is asked for an
   * ordering it cannot derive.
   */
  List<ChatReasoning> findByConversationId(String conversationId);

  /**
   * Called when the conversation goes, or its thinking would outlive what it was thinking about.
   */
  void deleteByConversationId(String conversationId);
}
