package me.kezhenxu94.springagent.persistence.redis.repo;

import java.time.Instant;
import me.kezhenxu94.springagent.core.dao.models.ProcessedMessage;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis implementation, registered when this module is the persistence backend in play.
 *
 * <p>A class rather than a Spring Data interface, unlike every other repository here — see {@code
 * JpaProcessedMessageRepo} for the reasoning, which applies more strongly still: {@code SET NX EX}
 * is this whole contract in one command, and a {@code CrudRepository} would only imply that the
 * model is stored as a hash, which it never is.
 *
 * <p>So the claim is a plain string key rather than the model's {@code @RedisHash} keyspace.
 * Nothing is ever read back out of it — the existence of the key is the entire record — and {@code
 * save} through a repository could not have been used anyway, being an unconditional write where
 * the point is to refuse the second one.
 *
 * <p>The key is set without an expiry, deliberately — see {@link ProcessedMessageRepo#claim}. Note
 * for this backend in particular that this is a record and not a cache, which is the same thing
 * {@code RedisPersistenceAutoConfiguration} says about everything else stored here: a Redis
 * provisioned with an {@code allkeys-lru} policy is free to drop a claim, and a dropped claim is a
 * message that can be answered twice.
 */
public class RedisProcessedMessageRepo implements ProcessedMessageRepo {

  private final StringRedisTemplate redisTemplate;

  public RedisProcessedMessageRepo(final StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  @Override
  public boolean claim(final String id) {
    // The value is when it was claimed. Nothing reads it, but a key found by hand during an
    // incident says more than an empty one would.
    //
    // Boolean.TRUE.equals rather than a cast: setIfAbsent answers null when pipelined or inside a
    // transaction, and while this is neither, a null would unbox and throw rather than read as a
    // loss.
    return Boolean.TRUE.equals(
        redisTemplate.opsForValue().setIfAbsent(key(id), Instant.now().toString()));
  }

  @Override
  public void release(final String id) {
    redisTemplate.delete(key(id));
  }

  private static String key(final String id) {
    return ProcessedMessage.COLLECTION_NAME + ":claim:" + id;
  }
}
