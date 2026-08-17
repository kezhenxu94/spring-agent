package me.kezhenxu94.springagent.persistence.mongodb.repo;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ProcessedMessage;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The MongoDB implementation, registered when this module is the persistence backend in play.
 *
 * <p>A class rather than a Spring Data interface, unlike every other repository here: the contract
 * has one operation, none of it is derivable, and it is written against the {@code MongoTemplate}
 * throughout — see {@code JpaProcessedMessageRepo} for the same reasoning.
 *
 * <p>{@code insert} rather than {@code save}: save upserts, so it would happily overwrite the claim
 * it is meant to be losing to. Insert is refused by the {@code _id} index, and that refusal —
 * narrowly a {@link DuplicateKeyException}, not a general failure — is what tells this caller it
 * lost. No read precedes the write, so there is no window between checking and claiming.
 */
@Slf4j
public class MongoProcessedMessageRepo implements ProcessedMessageRepo {

  private final MongoTemplate mongoTemplate;

  public MongoProcessedMessageRepo(final MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public boolean claim(final String id) {
    try {
      mongoTemplate.insert(ProcessedMessage.builder().id(id).createdAt(Instant.now()).build());
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  @Override
  public void release(final String id) {
    mongoTemplate.remove(Query.query(where("_id").is(id)), ProcessedMessage.class);
  }
}
