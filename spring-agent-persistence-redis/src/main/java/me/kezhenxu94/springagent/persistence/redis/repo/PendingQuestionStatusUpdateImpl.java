package me.kezhenxu94.springagent.persistence.redis.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import org.springframework.data.redis.core.PartialUpdate;
import org.springframework.data.redis.core.RedisKeyValueTemplate;

/**
 * The {@code Impl} suffix is not decoration: Spring Data composes a repository from the fragment
 * interfaces it extends by looking for exactly {@code <FragmentInterface>Impl}, so renaming this
 * class detaches it and {@code updateStatus} starts failing as an underivable query.
 *
 * <p>A {@link PartialUpdate} rather than a read-modify-write keeps the {@code status} secondary
 * index correct, which {@code findByConversationIdAndStatus} depends on — see {@link
 * ScheduledTaskStatusUpdateImpl} for the longer version.
 */
public class PendingQuestionStatusUpdateImpl implements PendingQuestionStatusUpdate {

  private final RedisKeyValueTemplate template;

  public PendingQuestionStatusUpdateImpl(final RedisKeyValueTemplate template) {
    this.template = template;
  }

  @Override
  public void updateStatus(final String id, final PendingQuestion.Status status) {
    template.update(
        PartialUpdate.newPartialUpdate(id, PendingQuestion.class).set("status", status));
  }
}
