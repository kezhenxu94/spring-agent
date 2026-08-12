package me.kezhenxu94.springagent.app;

import com.lark.oapi.ws.Client;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class AbstractIntegrationTest {

  @Container @ServiceConnection
  static final MongoDBContainer mongoDBContainer =
      new MongoDBContainer(DockerImageName.parse("mongo:8"));

  // The tool-search advisor runs with a vector tool index, so the context cannot refresh without a
  // vector store — but the default one is in-heap, so no container is needed for it here.
  // VectorStoreMilvusTest brings its own Milvus, being the only test that selects it.

  @MockitoBean Client feishuClient;
  @MockitoBean KubernetesClient kubernetesClient;

  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {
    // Per-JVM rather than application.yaml's fixed ./data/spring-agent.db: tests run in up to eight
    // parallel forks against the same working directory, and SQLite serialises writers across
    // processes, so a shared file makes them contend for locks.
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + databaseFile());
    registry.add("app.ai.tools.shell-pod.enabled", () -> "true");
    registry.add("app.ai.tools.shell-pod.image", () -> "test-image:latest");
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
  }

  private static Path databaseFile() {
    try {
      return Files.createTempDirectory("spring-agent-db").resolve("spring-agent.db");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
