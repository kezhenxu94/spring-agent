package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.milvus.MilvusContainer;
import org.testcontainers.utility.DockerImageName;

/** Selecting milvus swaps the whole vector store, leaving the simple one out of the context. */
@SpringBootTest(properties = "spring.ai.vectorstore.type=milvus")
class VectorStoreMilvusTest extends AbstractIntegrationTest {

  // Only this test needs a Milvus, which is why the container lives here and not in the base class.
  @Container @ServiceConnection
  static final MilvusContainer milvusContainer =
      new MilvusContainer(DockerImageName.parse("milvusdb/milvus:v2.4.13"));

  @Autowired ApplicationContext context;
  @Autowired VectorStore vectorStore;

  @Test
  @DisplayName("the only vector store is the Milvus one")
  void milvusBacksTheVectorStore() {
    assertThat(context.getBeansOfType(VectorStore.class)).hasSize(1);
    assertThat(vectorStore).isInstanceOf(MilvusVectorStore.class);
  }
}
