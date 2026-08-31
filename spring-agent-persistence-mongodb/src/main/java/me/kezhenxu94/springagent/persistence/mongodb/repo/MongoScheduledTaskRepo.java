package me.kezhenxu94.springagent.persistence.mongodb.repo;

import java.time.Instant;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoScheduledTaskRepo
    extends ScheduledTaskRepo, MongoRepository<ScheduledTask, String> {

  @Override
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'status': ?1 } }")
  void updateStatus(String id, ScheduledTask.Status status);

  @Override
  @Query("{ '_id': ?0 }")
  // $inc treats a missing field as zero, so a task written before the field existed counts from
  // one.
  @Update("{ '$inc': { 'runCount': 1 } }")
  void incrementRunCount(String id);

  // The two conditional writes behind claimNextFireAt and initNextFireAt. The predicate is in the
  // query — the filter of the update — so the server decides who wins: two replicas issuing this
  // for the same occurrence both send an update, and only the one that runs first matches a
  // document, the other having already had nextFireAt moved off ?1 beneath it.
  //
  // Returning long, the modified count, because that is what @Update offers; adapted to the
  // contract's boolean below.
  @Query("{ '_id': ?0, 'nextFireAt': ?1 }")
  @Update("{ '$set': { 'nextFireAt': ?2 } }")
  long advanceNextFireAt(String id, Instant expected, Instant next);

  // 'nextFireAt': null matches a document whose field is null *and* one that has no such field at
  // all, which is what a task written before this field existed looks like. That equivalence is
  // what lets the upgrade happen with no migration.
  @Query("{ '_id': ?0, 'nextFireAt': null }")
  @Update("{ '$set': { 'nextFireAt': ?1 } }")
  long setFirstNextFireAt(String id, Instant next);

  @Override
  default boolean claimNextFireAt(final String id, final Instant expected, final Instant next) {
    return advanceNextFireAt(id, expected, next) > 0;
  }

  @Override
  default boolean initNextFireAt(final String id, final Instant next) {
    return setFirstNextFireAt(id, next) > 0;
  }
}
