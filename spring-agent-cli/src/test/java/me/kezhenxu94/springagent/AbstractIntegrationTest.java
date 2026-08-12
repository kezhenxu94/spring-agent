package me.kezhenxu94.springagent;

import com.lark.oapi.ws.Client;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class AbstractIntegrationTest {

  @Container @ServiceConnection
  static final MongoDBContainer mongoDBContainer =
      new MongoDBContainer(DockerImageName.parse("mongo:8"));

  // The tool-search advisor runs with a vector tool index, so the context cannot refresh without a
  // reachable Milvus. application.yaml points at the in-cluster host, which does not resolve
  // outside the cluster.
  @Container @ServiceConnection
  static final MilvusContainer milvusContainer =
      new MilvusContainer(DockerImageName.parse("milvusdb/milvus:v2.4.13"));

  @MockitoBean Client feishuClient;
  @MockitoBean KubernetesClient kubernetesClient;

  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {
    registry.add("app.ai.tools.shell-pod.enabled", () -> "true");
    registry.add("app.ai.tools.shell-pod.image", () -> "test-image:latest");
    registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:8080");
    registry.add("spring.ai.openai.api-key", () -> "test-openai-key");
    registry.add("spring.ai.openai.chat.model", () -> "test-openai-model");
    registry.add("spring.ai.openai.audio.transcription.base-url", () -> "http://127.0.0.1:8080");
    registry.add(
        "spring.ai.openai.audio.transcription.api-key", () -> "test-transcription-openai-key");
  }
}
