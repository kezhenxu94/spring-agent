package me.kezhenxu94.springagent.integration.slack.config;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.MethodsClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.integration.slack.aot.SlackRuntimeHints;
import me.kezhenxu94.springagent.integration.slack.aot.SlackSdkRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Wires the Slack integration: the Web API client plus every {@code @Component} under {@code
 * me.kezhenxu94.springagent.integration.slack}.
 *
 * <p>Set {@code app.slack.enabled=false} to leave all of it out — importantly including {@code
 * SlackEventHandler}, which opens a Socket Mode connection to Slack as soon as it is created. The
 * switch is a dedicated flag rather than a check on {@code app.slack.bot-token} because conditions
 * are evaluated against raw property values, and the credentials are configured as {@code
 * ${SLACK_BOT_TOKEN}} placeholders that fail to resolve precisely when Slack is not set up.
 *
 * <p><b>This module and {@code spring-agent-integration-feishu} must not share a runtime
 * classpath.</b> Each ships a {@code @Bean AgentResponseListener} that claims every run, a {@code
 * PromptVariablesContributor} that fills {@code replyFormat} unconditionally, and a {@code
 * Notifier}; with both present the first two answer for each other's runs and the third makes
 * {@code SituationSweeper}'s {@code getIfAvailable()} throw. That is why this repository ships
 * {@code spring-agent-app-feishu} and {@code spring-agent-app-slack} rather than one server — see
 * {@code docs/sdk.md}.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.slack",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SlackAutoConfiguration.class))
@EnableConfigurationProperties(SlackProperties.class)
@ImportRuntimeHints({SlackRuntimeHints.class, SlackSdkRuntimeHints.class})
@ConditionalOnProperty(
    prefix = "app.slack",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SlackAutoConfiguration {

  /** The bean name the handlers that hand work off to Slack ask for. */
  public static final String TASK_EXECUTOR = "slackTaskExecutor";

  /**
   * Somewhere to put work that waits on Slack.
   *
   * <p>Its own, rather than Boot's {@code applicationTaskExecutor}, which is declared
   * {@code @ConditionalOnMissingBean(Executor.class)} and so exists only in an application that
   * registers no executor of its own. That is not a property this module can assume of whoever
   * embeds it — a STOMP application registers four — and borrowing it means failing to start,
   * naming a Boot bean, in an application that had done nothing wrong. The same fix, for the same
   * reason, as {@code FeishuAutoConfiguration.feishuTaskExecutor}.
   *
   * <p>Not {@code taskScheduler} either: its threads exist to fire scheduled tasks on time, and
   * this work sits blocked on a Slack call. Virtual threads because that is what this work is, with
   * a concurrency limit so a burst cannot start unbounded work.
   */
  @Bean(TASK_EXECUTOR)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = TASK_EXECUTOR)
  public org.springframework.core.task.TaskExecutor slackTaskExecutor() {
    final var executor = new org.springframework.core.task.SimpleAsyncTaskExecutor("slack-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(64);
    return executor;
  }

  /**
   * This module's tool translations, which core applies along with its own — keyed to {@code
   * app.slack.locale} rather than {@code app.locale}, as everything else this module writes is.
   */
  @Bean
  ToolTexts slackToolTexts(final SlackProperties properties) {
    return new ModuleToolTexts("slack/tools", "slack/prompts/tools/", properties.locale());
  }

  /**
   * The clock a message puts itself back on when it is holding an update that nothing followed —
   * the last chunk before a tool call, the end of an answer. See {@code SlackMessage#stream}.
   *
   * <p>Two threads is generous: all this does is hand a message's queue to {@link
   * #slackMessageWrites()}, which is where the call is actually made, so a slow channel cannot hold
   * a thread of this pool at all.
   *
   * <p>Daemon threads, and {@code shutdownNow} to close: what is queued here is a message update,
   * and a JVM must not stay up for one.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "slackMessageFlushes")
  ScheduledExecutorService slackMessageFlushes() {
    return Executors.newScheduledThreadPool(
        2,
        runnable -> {
          final var thread = new Thread(runnable, "slack-message-clock");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Where every call a streaming message makes to Slack is made, so that none of them is made on
   * the thread consuming the model's stream.
   *
   * <p>A thread per write rather than a pool, because the thread is held for a whole round trip —
   * and, when Slack answers a burst with 429, for the {@code Retry-After} it names as well. The
   * number of messages being written to at once is the number of turns in flight, which is not
   * something this can be sized for, and a pool of any fixed size would make one rate-limited
   * channel the reason every other channel's answer stopped appearing. Virtual, so that waiting on
   * Slack costs a stack and not a thread; a message serializes its own writes itself.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "slackMessageWrites")
  ExecutorService slackMessageWrites() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("slack-message-write-", 1).factory());
  }

  /**
   * The Web API client, with a timeout stated rather than left to the SDK.
   *
   * <p>What an unbounded call costs is not a missed message update but a stuck turn: a subagent
   * tells its parent's message it has finished before the run waiting on it is released, so one
   * call Slack never answers hangs the whole turn, silently.
   */
  @Bean
  @ConditionalOnMissingBean
  Slack slack(final SlackProperties properties) {
    final var config = new SlackConfig();
    final var millis = (int) properties.requestTimeout().toMillis();
    config.setHttpClientCallTimeoutMillis(millis);
    config.setHttpClientReadTimeoutMillis(millis);
    config.setHttpClientWriteTimeoutMillis(millis);
    return Slack.getInstance(config);
  }

  @Bean
  @ConditionalOnMissingBean
  MethodsClient slackMethods(final Slack slack, final SlackProperties properties) {
    return slack.methods(properties.botToken());
  }
}
