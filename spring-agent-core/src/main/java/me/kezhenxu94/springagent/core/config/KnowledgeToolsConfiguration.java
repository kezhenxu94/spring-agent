package me.kezhenxu94.springagent.core.config;

import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBaseTools;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Offers the knowledge base tools to a run, but only where something implements the knowledge base.
 *
 * <p>Core ships no implementation — one arrives with a {@code spring-agent-rag-*} module — so a
 * deployment without one has no knowledge base at all, and offering the model tools that could only
 * fail would be worse than offering nothing. That is an ordinary configuration rather than a broken
 * one, which is why this backs off silently instead of failing to start.
 *
 * <p>{@code afterName} rather than {@code after} is the load-bearing part.
 * {@code @ConditionalOnBean} is answered against the beans registered by the time it runs, so an
 * auto-configuration contributing the implementation afterwards would lose silently — the same
 * failure {@code ToolSearchIndexConfiguration} documents, and just as quiet. Naming the class as a
 * string is what lets core order itself after a module it must not depend on; a class literal would
 * be a compile dependency pointing the wrong way through the SPI.
 *
 * <p>The name is matched textually, so a module renaming its auto-configuration silently loses its
 * tools. Each {@code spring-agent-rag-*} module's class has to be listed here.
 *
 * <p>{@code @AgentTool} on the bean method rather than the class: the annotation is honoured on
 * factory methods, which is what lets a conditionally registered tool still be discovered by {@code
 * AgentToolsProvider.resolveScenarioTools}.
 */
@AutoConfiguration(
    afterName = "me.kezhenxu94.springagent.rag.milvus.MilvusKnowledgeAutoConfiguration")
public class KnowledgeToolsConfiguration {

  @Bean
  @AgentTool
  @ConditionalOnBean(KnowledgeBase.class)
  @ConditionalOnMissingBean
  KnowledgeBaseTools knowledgeBaseTools(
      final KnowledgeBase knowledgeBase,
      final UserWorkspaceFactory userWorkspaceFactory,
      final SpringAgentProperties properties,
      final CoreMessages messages) {
    return new KnowledgeBaseTools(knowledgeBase, userWorkspaceFactory, properties, messages);
  }

  /**
   * Filing something away verbatim is the one thing done to a large tool result that does not
   * involve reading it, so {@code text} takes a reference to one rather than the model retyping a
   * document to store it unchanged.
   *
   * <p>Registered beside the tools and under the same condition: a parameter that accepts a
   * reference on a tool this deployment does not have is a rule about nothing.
   */
  @Bean
  @ConditionalOnBean(KnowledgeBase.class)
  ToolInputFileRefs.Params knowledgeToolFileRefParams() {
    return () -> Map.of("IndexKnowledge", Set.of("text"));
  }
}
