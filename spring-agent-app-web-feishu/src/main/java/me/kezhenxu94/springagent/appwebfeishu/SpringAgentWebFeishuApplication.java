package me.kezhenxu94.springagent.appwebfeishu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The agent, a Feishu bot, and a browser to use the same conversations from.
 *
 * <p>{@code spring-agent-app-webui} with the Feishu surface added, and the only application here
 * that carries two surfaces at once. It exists because a handoff between them cannot be done from
 * two processes: a run journal is held in memory, so a browser can only watch a Feishu run as it
 * happens if that run is in this JVM, and putting a browser's answer back on the Feishu chat needs
 * a Feishu client in the process that produced the answer.
 *
 * <p><b>Two surfaces is a licence this application has and the others do not.</b> Three singletons
 * in this runtime answer for every run rather than for one surface's — see {@code
 * OneChatSurfaceTest} in {@code spring-agent-app-slack} — and each is answered here by exactly one
 * of the two: Feishu's card listener and its reply-format contributor both decline a run whose
 * {@code chatType} is not theirs, the browser's listener claims only its own unless {@code
 * app.web.follow-chat-runs} says otherwise, and only Feishu ships a {@code Notifier}. A second
 * <em>chat</em> surface would still be the bug that test describes, which is why {@code
 * OneChatSurfacePlusWebTest} here asserts the shape rather than relaxing the rule.
 *
 * <p>The two things this module contributes of its own are a {@link SecurityConfigurer} and a
 * {@link FeishuIdentityCheck}. Everything else — the pages, the mirror, the cards — lives in the
 * two published integration modules.
 *
 * <p>{@code @EnableScheduling} for {@code RunJournals}' sweep and for the runtime's own task
 * scheduler. Neither is auto-configured — Boot supplies a scheduler and never the annotation that
 * makes {@code @Scheduled} mean anything — so an application including that module has to say this.
 */
@EnableScheduling
@SpringBootApplication
public class SpringAgentWebFeishuApplication {

  public static void main(final String[] args) {
    SpringApplication.run(SpringAgentWebFeishuApplication.class, args);
  }
}
