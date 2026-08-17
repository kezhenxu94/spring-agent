package me.kezhenxu94.springagent.persistence.jpa.repo;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.ProcessedMessage;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import org.springframework.transaction.annotation.Transactional;

/**
 * The JPA implementation, registered when this module is the persistence backend in play.
 *
 * <p>A class rather than a Spring Data interface, unlike every other repository here. The contract
 * has one operation, no part of it is derivable — there is no such thing as a derived insert, let
 * alone a conditional one — and it is written against the {@code EntityManager} throughout, so
 * extending {@code JpaRepository} would contribute nothing but a set of inherited methods nobody
 * calls.
 *
 * <p>The insert is written by hand, in SQL, for two reasons that both come down to wanting the
 * database to arbitrate without anything being thrown.
 *
 * <p>{@code on conflict do nothing} makes the row count the answer: one row means this caller
 * claimed the message, none means somebody already had. The obvious alternative — persist, and read
 * the constraint violation as a loss — cannot be caught here without consequence. An exception from
 * the persistence context marks the transaction rollback-only, so returning normally afterwards
 * fails the commit instead; and a violation is not reliably distinguishable from a database that is
 * simply unreachable, which would have a lost connection read as "already answered" and drop the
 * message silently. Dropping a message is a worse failure than answering one twice, which is the
 * whole reason this class exists.
 *
 * <p>The syntax is SQLite's and PostgreSQL's, which is what {@code spring.datasource.url} defaults
 * to and the realistic alternative for a deployment. On a database without it the statement fails
 * loudly, and the caller treats that as the message not having been claimed — so it is retried
 * rather than lost.
 */
@Slf4j
public class JpaProcessedMessageRepo implements ProcessedMessageRepo {

  private static final String CLAIM =
      "insert into "
          + ProcessedMessage.COLLECTION_NAME
          + " (id, created_at) values (:id, :createdAt) on conflict do nothing";

  private static final String RELEASE =
      "delete from " + ProcessedMessage.COLLECTION_NAME + " where id = :id";

  private final EntityManager entityManager;

  public JpaProcessedMessageRepo(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional
  public boolean claim(final String id) {
    return entityManager
            .createNativeQuery(CLAIM)
            .setParameter("id", id)
            .setParameter("createdAt", Instant.now())
            .executeUpdate()
        > 0;
  }

  @Override
  @Transactional
  public void release(final String id) {
    entityManager.createNativeQuery(RELEASE).setParameter("id", id).executeUpdate();
  }
}
