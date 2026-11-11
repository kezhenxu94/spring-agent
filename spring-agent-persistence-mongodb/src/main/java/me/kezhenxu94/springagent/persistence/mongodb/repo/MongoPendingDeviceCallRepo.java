package me.kezhenxu94.springagent.persistence.mongodb.repo;

import me.kezhenxu94.springagent.core.dao.models.PendingDeviceCall;
import me.kezhenxu94.springagent.core.dao.repo.PendingDeviceCallRepo;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

/** The MongoDB implementation, registered when this module is the persistence backend in play. */
public interface MongoPendingDeviceCallRepo
    extends PendingDeviceCallRepo, MongoRepository<PendingDeviceCall, String> {

  @Override
  @Query("{ '_id': ?0 }")
  @Update("{ '$set': { 'status': ?1 } }")
  void updateStatus(String id, PendingDeviceCall.Status status);
}
