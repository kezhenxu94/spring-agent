package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.persistence.jpa.repo.JpaMcpServerConfigRepo;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoMcpServerConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The default backend: JPA over the spring.datasource database, with no MongoDB in the context. */
@SpringBootTest
class PersistenceJpaTest extends AbstractPersistenceBackendTest {

  @Autowired ApplicationContext context;

  @Override
  String owner() {
    return "ou_jpa";
  }

  @Test
  @DisplayName("the repositories are the JPA ones, and the MongoDB ones are absent")
  void jpaBacksThePersistence() {
    assertThat(mcpServerConfigRepo).isInstanceOf(JpaMcpServerConfigRepo.class);
    assertThat(context.getBeansOfType(MongoMcpServerConfigRepo.class)).isEmpty();
  }
}
