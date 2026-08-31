package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.persistence.jpa.repo.JpaMcpServerConfigRepo;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoMcpServerConfigRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Selecting mongodb swaps every repository over, and leaves the JPA ones out of the context. */
@SpringBootTest(properties = "app.persistence.type=mongodb")
class PersistenceMongoTest extends AbstractPersistenceBackendTest {

  @Autowired ApplicationContext context;

  @Override
  String owner() {
    return "ou_mongo";
  }

  @Test
  @DisplayName("the repositories are the MongoDB ones, and the JPA ones are absent")
  void mongoBacksThePersistence() {
    assertThat(mcpServerConfigRepo).isInstanceOf(MongoMcpServerConfigRepo.class);
    assertThat(context.getBeansOfType(JpaMcpServerConfigRepo.class)).isEmpty();
  }
}
