package me.kezhenxu94.springagent.app;

import com.slack.api.bolt.jakarta_socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.auth.AuthTestResponse;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class AbstractIntegrationTest {

  private static final int REDIS_PORT = 6379;

  @Container @ServiceConnection
  static final MongoDBContainer mongoDBContainer =
      new MongoDBContainer(DockerImageName.parse("mongo:8"));

  /**
   * Redis 8 rather than an earlier line: the chat memory repository stores messages as RedisJSON
   * and reads them through a RediSearch index, and 8 is the first release with both built in.
   *
   * <p>A {@link GenericContainer} because testcontainers ships no Redis module in the version this
   * project uses, and {@code @ServiceConnection} would not be enough anyway — it fills in {@code
   * spring.data.redis}, which serves the repositories, while Spring AI's chat memory builds its own
   * Jedis client from {@code spring.ai.chat.memory.repository.redis}. Both are wired below.
   */
  @Container
  static final GenericContainer<?> redisContainer =
      new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(REDIS_PORT);

  // The tool-search advisor runs with a vector tool index, so the context cannot refresh without a
  // vector store — but the default one is in-heap, so no container is needed for it here.
  // VectorStoreMilvusTest brings its own Milvus, being the only test that selects it.

  /**
   * Where the simple vector store's index goes in a test, named here so every test agrees on it.
   *
   * <p>Nested one directory down on purpose: the directory does not exist, so whichever test
   * persists the index also covers the store creating its parent.
   */
  static final Path VECTOR_STORE_FILE = tempVectorStoreFile();

  /**
   * Mocked so that no context in this suite opens a Socket Mode connection. Without it every test
   * would dial Slack with the placeholder token below, and the failure would be a slow one — a
   * retrying websocket client rather than a refused bean.
   */
  @MockitoBean SocketModeApp slackSocketModeApp;

  /**
   * A Slack that answers {@code auth.test}, because {@code SlackIdentity} asks it during context
   * refresh and refuses to start on a token Slack rejects — which the placeholder below is.
   *
   * <p>A real bean rather than a {@code @MockitoBean}: the stubbing has to be in place before the
   * context refreshes, and a mock field is only stubbable afterwards. It is also deliberately not a
   * bean of this module's own package, so that {@code SlackDisabledTest} can still assert that
   * nothing from there is registered.
   */
  @org.springframework.boot.test.context.TestConfiguration
  static class SlackStub {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    MethodsClient slackMethods() throws Exception {
      final var response = new AuthTestResponse();
      response.setOk(true);
      response.setUserId("U0TESTBOT");
      response.setTeamId("T0TESTTEAM");
      final var client = org.mockito.Mockito.mock(MethodsClient.class);
      org.mockito.Mockito.when(
              client.authTest(
                  org.mockito.ArgumentMatchers.any(com.slack.api.RequestConfigurator.class)))
          .thenReturn(response);
      return client;
    }
  }

  @MockitoBean KubernetesClient kubernetesClient;

  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {
    // Per-JVM rather than application.yaml's fixed ./data/spring-agent.db: tests run in up to eight
    // parallel forks against the same working directory, and SQLite serialises writers across
    // processes, so a shared file makes them contend for locks.
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + databaseFile());
    // And the simple vector store's file, for a sharper version of the same reason. Left at the
    // yaml's data/vectorstore.json, every application context in the suite parses whatever index
    // the
    // developer's own runs have left in the working directory — a file that grows without bound,
    // reaches hundreds of megabytes in ordinary use, and is held in the heap once per context. The
    // failure is an OutOfMemoryError somewhere unrelated, and it arrives as a function of how much
    // the machine has been used rather than of anything in the code. A path that does not exist is
    // also the cheap case: a missing file means an empty index, and these tests embed nothing.
    registry.add("app.ai.vectorstore.simple.file", VECTOR_STORE_FILE::toString);
    // Both prefixes, from the one container: spring.data.redis serves the repositories and
    // spring.ai.chat.memory.repository.redis the conversation history, and Spring AI's client
    // ignores Boot's connection details.
    registry.add("spring.data.redis.host", redisContainer::getHost);
    registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(REDIS_PORT));
    registry.add("spring.ai.chat.memory.repository.redis.host", redisContainer::getHost);
    registry.add(
        "spring.ai.chat.memory.repository.redis.port",
        () -> redisContainer.getMappedPort(REDIS_PORT));
    // The client above is mocked, so no cluster is contacted.
    registry.add("app.ai.tools.shell.type", () -> "kubernetes");
    registry.add("app.ai.tools.shell.kubernetes.image", () -> "test-image:latest");
    // application.yaml declares mounts[0] with its pvc-name left to an environment variable that
    // is unset here, and a mount without one is rejected outright, so name it.
    registry.add("app.ai.tools.shell.kubernetes.storage.mounts[0].pvc-name", () -> "test-pvc");
    registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:8080");
    registry.add("spring.ai.openai.api-key", () -> "test-openai-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-openai-model");
    // application.yaml points embeddings at ${EMBEDDING_BASE_URL} with no default, so without
    // these the context only refreshes on a machine that happens to export it.
    registry.add("spring.ai.openai.embedding.base-url", () -> "http://127.0.0.1:8080");
    registry.add("spring.ai.openai.embedding.api-key", () -> "test-embedding-key");
    registry.add("spring.ai.openai.embedding.model", () -> "test-embedding-model");
    registry.add("spring.ai.openai.audio.transcription.base-url", () -> "http://127.0.0.1:8080");
    registry.add(
        "spring.ai.openai.audio.transcription.api-key", () -> "test-transcription-openai-key");
    // application.yaml names these with no default, the way it names the model credentials: they
    // are what the deployment must supply, and a test is a deployment that supplies nothing real.
    registry.add("app.slack.bot-token", () -> "xoxb-test-bot-token");
    registry.add("app.slack.app-token", () -> "xapp-test-app-token");
    registry.add("app.slack.bot-user-id", () -> "U0TESTBOT");
    registry.add("app.slack.team-id", () -> "T0TESTTEAM");
    registry.add("spring.security.oauth2.client.registration.slack.client-id", () -> "test-client");
    registry.add(
        "spring.security.oauth2.client.registration.slack.client-secret", () -> "test-secret");
  }

  private static Path tempVectorStoreFile() {
    try {
      return Files.createTempDirectory("spring-agent-vectorstore").resolve("nested/index.json");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path databaseFile() {
    try {
      return Files.createTempDirectory("spring-agent-db").resolve("spring-agent.db");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
