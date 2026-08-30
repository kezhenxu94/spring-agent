package me.kezhenxu94.springagent.appweb;

import me.kezhenxu94.springagent.appweb.aot.WebRuntimeHints;
import me.kezhenxu94.springagent.appweb.config.WebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The agent, and a browser to use it from.
 *
 * <p>Deliberately carries no integration: no bot, no webhook receiver, no chat platform. What it
 * demonstrates is the runtime itself — one {@code SpringAgent}, one {@code AgentResponseListener},
 * and a page that renders everything a run emits. Feishu appears only as a way of logging in, which
 * is Spring Security and a tenant key rather than the Feishu SDK.
 *
 * <p>{@code @EnableScheduling} for {@code RunJournals}' sweep. The runtime's own scheduler for
 * firing tasks is enabled by core.
 */
@EnableScheduling
@SpringBootApplication
@ImportRuntimeHints(WebRuntimeHints.class)
@EnableConfigurationProperties(WebProperties.class)
public class SpringAgentWebApplication {

  public static void main(final String[] args) {
    SpringApplication.run(SpringAgentWebApplication.class, args);
  }
}
