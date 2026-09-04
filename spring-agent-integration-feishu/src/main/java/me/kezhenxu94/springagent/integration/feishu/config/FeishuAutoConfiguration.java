package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolInputFileRefs;
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
 * FeishuLongConnection}, which opens a websocket to Feishu as soon as it is created. The switch is
 * a dedicated flag rather than a check on {@code app.feishu.app-id} because conditions are
 * evaluated against raw property values, and the credentials are configured as {@code
 * ${FEISHU_APP_ID}} placeholders that fail to resolve precisely when Feishu is not set up.
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

  /** The bean name the handlers that hand work off to Feishu ask for. */
  public static final String TASK_EXECUTOR = "feishuTaskExecutor";

  /**
   * Somewhere to put work that waits on Feishu.
   *
   * <p><b>Its own, rather than Boot's {@code applicationTaskExecutor}.</b> That bean is declared
   * {@code @ConditionalOnMissingBean(Executor.class)}, so it exists only in an application that
   * registers no executor of its own — and a STOMP application registers four before anything here
   * is created. So an application combining this module with a browser surface simply has no {@code
   * applicationTaskExecutor}, and the four classes here that asked for it by name failed to be
   * created at all: a startup failure naming a Boot bean, in an application that had done nothing
   * wrong. Borrowing a conditional bean of somebody else's is what was wrong.
   *
   * <p>Not {@code taskScheduler} either, which is a {@code TaskExecutor} too and would otherwise be
   * the obvious candidate: its threads exist to fire scheduled tasks on time, and this work sits
   * blocked on a Feishu call.
   *
   * <p>Virtual threads, because that is exactly what this work is — a card update or a run start
   * waiting on a network call — and a concurrency limit anyway, so a burst of callbacks cannot
   * start unbounded work. {@code @ConditionalOnMissingBean} by name, so a deployment that wants its
   * own pool here declares one and this backs off.
   */
  @Bean(TASK_EXECUTOR)
  @ConditionalOnMissingBean(name = TASK_EXECUTOR)
  public org.springframework.core.task.TaskExecutor feishuTaskExecutor() {
    final var executor = new org.springframework.core.task.SimpleAsyncTaskExecutor("feishu-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(64);
    return executor;
  }

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
   * The clock a card puts itself back on when it is holding an update that nothing followed — the
   * last chunk before a tool call, the end of an answer. See {@code FeishuCard#stream}.
   *
   * <p>Two threads is generous: all this does is hand a card's queue to {@link
   * #feishuCardWrites()}, which is where the call is actually made, so a slow card cannot hold a
   * thread of this pool at all.
   *
   * <p>Daemon threads, and {@code shutdownNow} to close: what is queued here is a card update, and
   * a JVM must not stay up for one.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "feishuCardFlushes")
  ScheduledExecutorService feishuCardFlushes() {
    return Executors.newScheduledThreadPool(
        2,
        runnable -> {
          final var thread = new Thread(runnable, "feishu-card-clock");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Where every call a card makes to Feishu is made, so that none of them is made on the thread
   * consuming the model's stream. See {@code FeishuCard}.
   *
   * <p>A thread per write rather than a pool, because the thread is held for a whole round trip and
   * the number of cards being written to at once is the number of turns in flight — not something
   * this can be sized for. A pool of any fixed size would make one slow card the reason every other
   * card's answer stopped appearing, which is the problem being solved here rather than a cost
   * worth trading for. Virtual, so that being blocked on Feishu costs a stack and not a thread; a
   * card serializes its own writes itself — the sequence a card refuses out of order is drawn by
   * whichever worker is draining that card's queue, and only ever one is.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "feishuCardWrites")
  ExecutorService feishuCardWrites() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("feishu-card-write-", 1).factory());
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

  /**
   * The document parameters that take an {@code @file:} reference to a saved tool result instead of
   * the payload itself.
   *
   * <p>These are the ones a previous call's result is handed to unchanged: a converted block tree
   * on its way to being inserted, or a set of block updates. Nothing else here is on the list —
   * everywhere else the model is composing the argument rather than passing one along, and a
   * parameter that accepts a reference is a parameter every tool call can be steered into reading a
   * file with.
   */
  @Bean
  ToolInputFileRefs.Params feishuDocToolFileRefParams() {
    return () ->
        Map.of(
            "FeishuCreateDocBlockDescendant", Set.of("descendantsJson"),
            "FeishuCreateDocBlockChildren", Set.of("childrenJson"),
            "FeishuBatchUpdateDocBlocks", Set.of("requestsJson"));
  }
}
