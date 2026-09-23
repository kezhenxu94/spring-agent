package me.kezhenxu94.springagent.core.config;

import me.kezhenxu94.springagent.core.advisors.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorAutoConfiguration;
import org.springframework.ai.chat.client.advisor.toolsearch.autoconfigure.ToolSearchAdvisorProperties;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.eviction.CompositeEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.LruEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.ToolIndexEvictionStrategy;
import org.springframework.ai.tool.toolsearch.eviction.TtlEvictionStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Registers {@link ToolSearchToolCallingAdvisor}'s builder in place of Spring AI's own {@code
 * org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor.Builder} — see
 * that class's javadoc for what differs and why.
 *
 * <p>{@code before = ToolSearchAdvisorAutoConfiguration.class} is load-bearing exactly the way
 * {@link ToolSearchIndexConfiguration}'s is: {@code ToolSearchAdvisorAutoConfiguration}'s own
 * builder bean is guarded by {@code @ConditionalOnMissingBean}, and a condition is answered against
 * the beans registered by the time it is evaluated. Registering this one first is what makes
 * upstream's own back off; registering it afterward would have this one back off instead, silently,
 * with the buggy builder left in place.
 *
 * <p><b>Neither {@link ToolIndex} nor {@link ToolCallingManager} is taken through
 * {@code @ConditionalOnBean}</b> — both are plain method parameters, resolved the ordinary way when
 * this bean is actually built, well after every auto-configuration has finished registering bean
 * definitions. A hard condition would be answered when this configuration is <em>processed</em>
 * instead, and being ordered {@code before ToolSearchAdvisorAutoConfiguration} for the reason above
 * means that happens before that class's own {@code ToolIndex} sub-configuration has run — and,
 * empirically, before the real {@code ToolCallingManager} a run gets is settled too: it is core's
 * own bean, not Spring AI's — see {@code SpringAgentCoreAutoConfiguration#toolCallingManager},
 * whose own comment states "this class sorts first" over Spring AI's — and the two configurations
 * declare no order between themselves, so which one a topological sort actually places first is not
 * something this class may assume. Gating on either type here failed exactly the way {@link
 * ToolSearchIndexConfiguration}'s javadoc describes for {@code VectorStore}: silently, with this
 * bean never registering and the buggy upstream builder left in its place.
 */
@AutoConfiguration(
    before = {ToolSearchAdvisorAutoConfiguration.class, ChatClientAutoConfiguration.class})
@ConditionalOnClass(ToolSearchToolCallingAdvisor.class)
@EnableConfigurationProperties(ToolSearchAdvisorProperties.class)
@ConditionalOnProperty(
    prefix = ToolSearchAdvisorProperties.CONFIG_PREFIX,
    name = "enabled",
    havingValue = "true")
public class SpringAgentToolSearchAdvisorConfiguration {

  /**
   * Registers a {@link ToolSearchToolCallingAdvisor.Builder} typed as {@code
   * ToolCallingAdvisor.Builder}, from the same {@link ToolSearchAdvisorProperties} Spring AI's own
   * auto-configuration reads — the fork changes one method of the advisor, not the shape of its
   * configuration, so a deployment moving between the two needs to change nothing it set.
   *
   * <p>Named differently from upstream's own {@code toolCallingAdvisorBuilder} bean method
   * deliberately, matching {@link ToolSearchIndexConfiguration#statelessVectorToolIndex} — two
   * {@code @Bean} methods sharing one name is a bean-definition collision by name, which Spring
   * resolves by overriding rather than by asking either method's {@code @ConditionalOnMissingBean}
   * at all. Only the type has to match for that condition to see this bean; the name must not.
   */
  @Bean
  @ConditionalOnMissingBean
  ToolCallingAdvisor.Builder<?> springAgentToolSearchAdvisorBuilder(
      final ToolSearchAdvisorProperties properties,
      final ObjectProvider<ToolCallingManager> toolCallingManager,
      final ObjectProvider<ToolIndex> toolIndex,
      final ObjectProvider<ToolExecutionEligibilityChecker> toolExecutionEligibilityChecker) {

    final var builder =
        ToolSearchToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager.getObject())
            .toolIndex(toolIndex.getIfAvailable())
            .advisorOrder(properties.getAdvisorOrder())
            .referenceToolNameAccumulation(properties.isReferenceToolNameAccumulation())
            .sessionIdKeyName(properties.getSessionIdKeyName())
            .evictionStrategy(buildEvictionStrategy(properties.getEviction()));

    if (properties.getMaxResults() != null) {
      builder.maxResults(properties.getMaxResults());
    }
    if (StringUtils.hasText(properties.getSystemMessageSuffix())) {
      builder.systemMessageSuffix(properties.getSystemMessageSuffix());
    }

    toolExecutionEligibilityChecker.ifAvailable(builder::toolExecutionEligibilityChecker);

    return builder;
  }

  private static ToolIndexEvictionStrategy buildEvictionStrategy(
      final ToolSearchAdvisorProperties.Eviction eviction) {
    final var lru = new LruEvictionStrategy(eviction.getLruMaxSessions());
    if (eviction.getTtl() != null) {
      return new CompositeEvictionStrategy(lru, new TtlEvictionStrategy(eviction.getTtl()));
    }
    return lru;
  }
}
