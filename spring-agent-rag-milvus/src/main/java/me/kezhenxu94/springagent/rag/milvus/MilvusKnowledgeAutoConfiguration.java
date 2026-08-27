package me.kezhenxu94.springagent.rag.milvus;

import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.rag.milvus.aot.MilvusKnowledgeRuntimeHints;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Supplies the {@link KnowledgeBase} core's tools and retrieval advisor look for.
 *
 * <p>Needs both this module on the classpath and {@code app.ai.rag.enabled}, and the property does
 * not default on. Being on the classpath is not consent here the way it is for the Feishu module,
 * because this one opens a connection at startup: a knowledge base needs a Milvus, the default
 * deployment runs none, and an auto-configuration that activated on presence alone would stop every
 * such application from starting. {@code app.ai.tools.shell.type} defaults to {@code none} for the
 * same reason.
 *
 * <p>There is no {@code app.ai.rag.type} switch because there is one implementation, and a switch
 * between one thing and nothing is what {@code enabled} already is. A second {@code
 * spring-agent-rag-*} module is when that changes, and the {@code @ConditionalOnShellBackend} trio
 * is the pattern to copy then.
 *
 * <p>This class's fully qualified name is named as a string in core's {@code
 * KnowledgeToolsConfiguration}, which has to be ordered after it — renaming or moving this means
 * updating that too, and nothing will fail loudly if it is forgotten: the tools would simply stop
 * being registered.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "app.ai.rag", name = "enabled")
@EnableConfigurationProperties(MilvusKnowledgeProperties.class)
@ImportRuntimeHints(MilvusKnowledgeRuntimeHints.class)
public class MilvusKnowledgeAutoConfiguration {

  @Bean(destroyMethod = "destroy")
  @ConditionalOnMissingBean(KnowledgeBase.class)
  MilvusKnowledgeBase milvusKnowledgeBase(
      final MilvusKnowledgeProperties properties,
      final SpringAgentProperties agentProperties,
      final EmbeddingModel embeddingModel) {
    return new MilvusKnowledgeBase(properties, agentProperties, embeddingModel);
  }
}
