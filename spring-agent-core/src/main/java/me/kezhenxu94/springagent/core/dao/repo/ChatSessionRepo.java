package me.kezhenxu94.springagent.core.dao.repo;

import java.util.List;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ChatSession;

/**
 * The contract the application uses, independent of which backend {@code app.persistence.type}
 * selected. Only the operations actually called are declared, and the method names have to remain
 * valid derived queries on every backend — see {@link ScheduledTaskRepo}.
 */
public interface ChatSessionRepo {

  ChatSession save(ChatSession session);

  Optional<ChatSession> findById(String id);

  /**
   * One person's conversations, in no particular order — the caller sorts.
   *
   * <p>Deliberately not {@code ...OrderByUpdatedAtDesc}: Spring Data Redis derives no ordering, and
   * a contract whose name promises one that a backend silently ignores is worse than one that
   * promises nothing. The list is one person's own conversations, so sorting it in memory costs
   * nothing worth a second index.
   */
  List<ChatSession> findByUserId(String userId);

  void deleteById(String id);
}
