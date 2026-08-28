package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.integration.feishu.aot.FeishuRuntimeHints;
import me.kezhenxu94.springagent.integration.feishu.aot.LarkSdkRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Wires the Feishu integration: the Lark client plus every {@code @Component} under {@code
 * me.kezhenxu94.springagent.integration.feishu}.
 *
 * <p>Set {@code app.feishu.enabled=false} to leave all of it out — importantly including {@link
 * FeishuEventHandler}, which opens a websocket to Feishu as soon as it is created. The switch is a
 * dedicated flag rather than a check on {@code app.feishu.app-id} because conditions are evaluated
 * against raw property values, and the credentials are configured as {@code ${FEISHU_APP_ID}}
 * placeholders that fail to resolve precisely when Feishu is not set up.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.feishu",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = FeishuAutoConfiguration.class))
@EnableConfigurationProperties(FeishuProperties.class)
@ImportRuntimeHints({FeishuRuntimeHints.class, LarkSdkRuntimeHints.class})
@ConditionalOnProperty(
    prefix = "app.feishu",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FeishuAutoConfiguration {

  /**
   * This module's tool translations, which core applies along with its own.
   *
   * <p>Its own rather than a few hundred more keys in core's, because two thirds of the tools this
   * deployment offers are declared here — the bitable, document and sheet tools alone come to
   * nearly three hundred strings — and their translations belong beside them, on a classpath core
   * does not read and in a module core must not know about. The same reasoning that gave {@link
   * FeishuMessages} a message source of its own.
   *
   * <p>Keyed to {@code app.feishu.locale} rather than {@code app.locale}, as everything else this
   * module writes is; the application defaults the one to the other.
   */
  /** The reference pages three of the tools return, read once in the configured language. */
  @Bean
  FeishuGuides feishuGuides(final FeishuProperties feishuProperties) {
    return new FeishuGuides(feishuProperties.locale());
  }

  @Bean
  ToolTexts feishuToolTexts(final FeishuProperties feishuProperties) {
    return new ModuleToolTexts(
        "feishu/tools", FeishuGuides.TOOLS_LOCATION, feishuProperties.locale());
  }

  /**
   * Where a card sends an update it held back when nothing followed it — the last chunk before a
   * tool call, the end of an answer. See {@code FeishuCard#stream}.
   *
   * <p>A pool rather than one thread because the write it makes is an HTTP call taken under the
   * card's lock, so a card whose writer is mid-call holds this thread until it returns; a single
   * thread would make one slow card the reason every other card's trailing update was late. Small
   * all the same: this is the only case a card cannot cover itself — its next chunk flushes
   * whatever is waiting, and so does the end of the run — and the cost of being late here is a card
   * that lags for one interval, not a lost update.
   *
   * <p>Daemon threads, and {@code shutdownNow} to close: what is queued here is a card update, and
   * a JVM must not stay up for one.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "feishuCardFlushes")
  ScheduledExecutorService feishuCardFlushes() {
    return Executors.newScheduledThreadPool(
        4,
        runnable -> {
          final var thread = new Thread(runnable, "feishu-card-flush");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Bean
  @ConditionalOnMissingBean
  Client feishuClient(final FeishuProperties feishuProperties) {
    return new Client.Builder(feishuProperties.appId(), feishuProperties.appSecret())
        .openBaseUrl(feishuProperties.baseUrl())
        // Stated rather than left to the SDK, whose default is no timeout: it builds its OkHttp
        // client with callTimeout(0), which means wait for ever. What that costs is not a missed
        // card update but a stuck turn — the card's writers share one lock and hold it across the
        // call, and a subagent tells its parent's card it has finished before the run waiting on
        // it is released. So one call Feishu never answers used to hang the whole turn, silently.
        // This is a callTimeout, so it bounds the whole call and not a gap within it.
        .requestTimeout(feishuProperties.requestTimeout().toMillis(), TimeUnit.MILLISECONDS)
        .build();
  }
}
