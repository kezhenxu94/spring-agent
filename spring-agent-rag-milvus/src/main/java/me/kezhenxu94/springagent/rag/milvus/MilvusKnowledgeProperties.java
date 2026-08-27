package me.kezhenxu94.springagent.rag.milvus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where this module's Milvus lives and what it keeps there.
 *
 * <p>Deliberately its own namespace rather than {@code spring.ai.vectorstore.milvus}. That one
 * configures the store backing the tool search index, and the two are unrelated concerns: a
 * deployment may reasonably run the tool index in the heap and the knowledge base in Milvus, which
 * sharing a namespace would make impossible to say.
 *
 * @param host the Milvus host; only read when this module is on the classpath
 * @param port the Milvus gRPC port
 * @param collectionName the collection holding knowledge chunks. Separate from the tool index's
 *     collection so that clearing or rebuilding one cannot touch the other, and so the two corpora
 *     do not dilute each other's searches
 * @param embeddingDimension must match the embedding model in use. Changing the model invalidates
 *     everything already stored: the tool index rebuilds itself, but the knowledge base does not,
 *     and re-indexing is a manual act
 * @param initializeSchema whether to create the collection when it is missing. On by default, since
 *     a knowledge base with nowhere to go is not a useful failure mode
 */
@ConfigurationProperties(prefix = "app.ai.rag.milvus")
public record MilvusKnowledgeProperties(
    String host,
    int port,
    String collectionName,
    int embeddingDimension,
    Boolean initializeSchema) {

  public MilvusKnowledgeProperties {
    if (host == null || host.isBlank()) {
      host = "localhost";
    }
    if (port <= 0) {
      port = 19530;
    }
    if (collectionName == null || collectionName.isBlank()) {
      collectionName = "spring_agent_knowledge";
    }
    if (embeddingDimension <= 0) {
      embeddingDimension = 1024;
    }
    if (initializeSchema == null) {
      initializeSchema = true;
    }
  }
}
