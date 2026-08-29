package me.kezhenxu94.springagent.events.config;

import java.time.Clock;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.events.aot.EventsRuntimeHints;
import me.kezhenxu94.springagent.events.situation.TriagePrompts;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Registers the event intelligence: the funnel a surface reports into, the sweep that decides when
 * something is worth an opinion, and the tools a run reads a situation with.
 *
 * <p>Off unless a deployment says otherwise, the same reasoning as {@code app.ai.tools.shell.type}
 * defaulting to {@code none} and {@code app.ai.rag.enabled} to false. Turning this on opens an
 * unauthenticated HTTP path and gives the agent licence to speak without being spoken to; a
 * half-configured deployment should do neither. {@link EventsDefaults} states the default {@code
 * false} rather than relying on {@code matchIfMissing}, so the value is written down once and read
 * by both this condition and the properties.
 *
 * <p>No {@code aot} package and no {@code @ImportRuntimeHints}, which is a decision rather than an
 * omission. What normally needs hints here would be the tools, and core's {@code
 * AgentToolsRuntimeHints} explains why they do not: a tool this project declares itself is found
 * through {@code @AgentTool} and already gets hints from Spring's own AOT processing. The webhook
 * sources read their payloads as trees rather than binding them to types, so nothing is reflected
 * over there either, and the configuration properties are handled by Boot's own AOT contribution.
 * Add a registrar here the moment any of those three stops being true.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.events",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = EventsAutoConfiguration.class))
@EnableConfigurationProperties(EventsProperties.class)
@ImportRuntimeHints(EventsRuntimeHints.class)
@ConditionalOnProperty(prefix = EventsProperties.PREFIX, name = "enabled", havingValue = "true")
public class EventsAutoConfiguration {

  /**
   * The clock every part of this module reads, rather than {@code Instant.now()} scattered through
   * it.
   *
   * <p>Debounce, cooldown and the quiet timeout are the whole behaviour of the feature, and they
   * are arithmetic on the current time. A test that has to wait thirty seconds to find out whether
   * a debounce works is a test nobody runs, so the time is injected and the tests move it.
   *
   * <p>{@code @ConditionalOnMissingBean} so an application that already has a {@code Clock} keeps
   * it — and so a test can put a fixed one in without excluding this configuration.
   */
  @Bean
  @ConditionalOnMissingBean
  Clock eventsClock() {
    return Clock.systemUTC();
  }

  /**
   * This module's tool translations, read off its own classpath.
   *
   * <p>A tool's English description is the annotation's own and stays there; this supplies the
   * translations beside it, so a workspace that speaks Chinese reads the situation tools in Chinese
   * as it reads everything else. A language nothing is translated into keeps the annotation, which
   * is why there is no base file to write.
   */
  @Bean
  ToolTexts eventsToolTexts(final SpringAgentProperties properties) {
    return new ModuleToolTexts(
        "events/tools", TriagePrompts.LOCATION + "tools/", properties.locale());
  }
}
