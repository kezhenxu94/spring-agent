package me.kezhenxu94.springagent.integration.telegram.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.integration.telegram.aot.TelegramRuntimeHints;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the Telegram integration: the Bot API client, the poll, and every {@code @Component} under
 * {@code me.kezhenxu94.springagent.integration.telegram}.
 *
 * <p>Set {@code app.telegram.enabled=false} to leave all of it out — including the poll, which
 * starts taking the bot's updates as soon as it is created. A dedicated flag rather than a check on
 * the token, for the reason {@code SlackAutoConfiguration} gives.
 *
 * <p><b>This module is a chat surface</b>, and must not share a runtime classpath with another one
 * — see {@code docs/integrations.md}. That is why it ships with an application of its own, {@code
 * spring-agent-app-telegram}.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.telegram",
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = TelegramAutoConfiguration.class))
@EnableConfigurationProperties(TelegramProperties.class)
@ImportRuntimeHints(TelegramRuntimeHints.class)
@ConditionalOnProperty(
    prefix = "app.telegram",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TelegramAutoConfiguration {

  /** The bean name the handlers that hand work off to Telegram ask for. */
  public static final String TASK_EXECUTOR = "telegramTaskExecutor";

  /**
   * Somewhere to put work that waits on Telegram — its own rather than Boot's {@code
   * applicationTaskExecutor}, for the reason {@code SlackAutoConfiguration.slackTaskExecutor}
   * gives.
   */
  @Bean(TASK_EXECUTOR)
  @ConditionalOnMissingBean(name = TASK_EXECUTOR)
  public TaskExecutor telegramTaskExecutor() {
    final var executor = new SimpleAsyncTaskExecutor("telegram-");
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(64);
    return executor;
  }

  /** This module's tool translations, keyed to {@code app.telegram.locale}. */
  @Bean
  ToolTexts telegramToolTexts(final TelegramProperties properties) {
    return new ModuleToolTexts("telegram/tools", "telegram/prompts/tools/", properties.locale());
  }

  /** The clock a reply puts itself back on — see {@code SlackAutoConfiguration}. */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "telegramMessageFlushes")
  ScheduledExecutorService telegramMessageFlushes() {
    return Executors.newScheduledThreadPool(
        2,
        runnable -> {
          final var thread = new Thread(runnable, "telegram-message-clock");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * Where every write a reply makes is made, a virtual thread each — a write can sleep out a 429
   * for as long as Telegram names, and one rate-limited group must not hold up every other chat.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "telegramMessageWrites")
  ExecutorService telegramMessageWrites() {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("telegram-message-write-", 1).factory());
  }

  @Bean
  @ConditionalOnMissingBean
  TelegramBotApi telegramBotApi(
      final RestClient.Builder restClients,
      final JsonMapper json,
      final TelegramProperties properties) {
    return new TelegramBotApi(
        restClients,
        json,
        properties.apiUrl(),
        properties.botToken(),
        properties.requestTimeout(),
        properties.pollTimeout());
  }

  /**
   * The poll, started once the context is up. No {@code destroyMethod}: {@link
   * TelegramPollingConnection} stops it, early enough in the shutdown to matter.
   *
   * <p>Depends on {@link TelegramIdentity} by parameter, though it never reads it, so that a token
   * Telegram refuses or a webhook left set fails startup before anything polls.
   */
  @Bean(initMethod = "start")
  @ConditionalOnMissingBean
  TelegramUpdatePoller telegramUpdatePoller(
      final TelegramBotApi telegram,
      final TelegramUpdateDispatcher dispatcher,
      final SpringAgent springAgent,
      final TelegramIdentity identity,
      final TelegramProperties properties) {
    return new TelegramUpdatePoller(telegram, dispatcher, springAgent, properties.pollTimeout());
  }
}
