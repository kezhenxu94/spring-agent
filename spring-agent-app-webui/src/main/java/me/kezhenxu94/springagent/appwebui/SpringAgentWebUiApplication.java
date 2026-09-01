package me.kezhenxu94.springagent.appwebui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The agent, and a browser to use it from.
 *
 * <p>Deliberately carries no chat platform: no bot, no webhook receiver. What it demonstrates is
 * the runtime itself — one {@code SpringAgent}, one {@code AgentResponseListener}, and a page that
 * renders everything a run emits. Feishu appears only as a way of logging in, which is Spring
 * Security and a tenant key rather than the Feishu SDK.
 *
 * <p>Two files, like {@code spring-agent-app-feishu}: this and a {@link SecurityConfigurer}.
 * Everything the browser actually talks to lives in {@code spring-agent-integration-websocket},
 * which is published, so an application of somebody else's can have the same surface by depending
 * on it.
 *
 * <p>{@code @EnableScheduling} for {@code RunJournals}' sweep and for the runtime's own task
 * scheduler. Neither is auto-configured — Boot supplies a scheduler and never the annotation that
 * makes {@code @Scheduled} mean anything — so an application including that module has to say this.
 */
@EnableScheduling
@SpringBootApplication
public class SpringAgentWebUiApplication {

  public static void main(final String[] args) {
    SpringApplication.run(SpringAgentWebUiApplication.class, args);
  }
}
