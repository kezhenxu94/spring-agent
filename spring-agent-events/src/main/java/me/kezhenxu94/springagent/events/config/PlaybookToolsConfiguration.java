package me.kezhenxu94.springagent.events.config;

import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.knowledge.KnowledgeBase;
import me.kezhenxu94.springagent.core.tools.AgentTool;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
import me.kezhenxu94.springagent.events.situation.PlaybookFilters;
import me.kezhenxu94.springagent.events.tools.PlaybookTools;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Offers {@link PlaybookTools} where there is a knowledge base to write a playbook into.
 *
 * <p>Separate from {@link EventsAutoConfiguration} rather than a bean on it, and for a reason worth
 * stating: this one has to be ordered after whichever module supplies the {@code KnowledgeBase},
 * and putting that ordering on the module's own auto-configuration would delay the whole funnel —
 * the intake, the sweep, the webhook path — behind a module that has nothing to do with any of it.
 *
 * <p>The {@code afterName} is load-bearing for the same reason {@code KnowledgeToolsConfiguration}
 * gives at length: {@code @ConditionalOnBean} is answered against what is registered by the time it
 * runs, so without the ordering an implementation contributed afterwards would lose silently and
 * the tools would simply not appear. The name is matched textually — rename that class and these
 * tools disappear with no error anywhere.
 *
 * <p>Gated on {@code app.events.enabled} as well, because a playbook is meaningless where nothing
 * triages: there would be no sources to write one for.
 */
@AutoConfiguration(
    afterName = "me.kezhenxu94.springagent.rag.milvus.MilvusKnowledgeAutoConfiguration")
@ConditionalOnProperty(prefix = EventsProperties.PREFIX, name = "enabled", havingValue = "true")
public class PlaybookToolsConfiguration {

  @Bean
  @AgentTool(admin = true)
  @ConditionalOnBean(KnowledgeBase.class)
  @ConditionalOnMissingBean
  PlaybookTools playbookTools(
      final KnowledgeBase knowledgeBase,
      final EventsProperties properties,
      final PlaybookFilters playbookFilters,
      final EventsMessages messages) {
    return new PlaybookTools(knowledgeBase, properties, playbookFilters, messages);
  }

  /**
   * A playbook rewritten wholesale is often a document that was read out of somewhere else, so
   * {@code text} takes a reference to the tool result it came from instead of the whole playbook
   * passing through the model twice.
   */
  @Bean
  @ConditionalOnBean(KnowledgeBase.class)
  ToolInputFileRefs.Params playbookToolFileRefParams() {
    return () -> Map.of("WritePlaybook", Set.of("text"));
  }
}
