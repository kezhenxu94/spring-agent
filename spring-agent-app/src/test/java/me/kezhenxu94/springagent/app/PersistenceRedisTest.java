package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.persistence.jpa.repo.JpaMcpServerConfigRepo;
import me.kezhenxu94.springagent.persistence.redis.repo.RedisMcpServerConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Selecting redis swaps every repository over, and leaves the JPA ones out of the context.
 *
 * <p>The inherited suite matters more here than for the other two backends. Redis cannot derive
 * {@code findBySharedWithIn}, {@code findAccessibleTo}, {@code deleteByOwnerIdAndName} or {@code
 * updateStatus}, so all four are hand-written in {@code spring-agent-persistence-redis} — and
 * {@code updateStatus} in particular has to leave the {@code status} secondary index correct, which
 * only a query filtering on it afterwards can show.
 */
@SpringBootTest(properties = "app.persistence.type=redis")
class PersistenceRedisTest extends AbstractPersistenceBackendTest {

  @Autowired ApplicationContext context;

  @Override
  String owner() {
    return "ou_redis";
  }

  @Test
  @DisplayName("the repositories are the Redis ones, and the JPA ones are absent")
  void redisBacksThePersistence() {
    assertThat(mcpServerConfigRepo).isInstanceOf(RedisMcpServerConfigRepo.class);
    assertThat(context.getBeansOfType(JpaMcpServerConfigRepo.class)).isEmpty();
  }
}
