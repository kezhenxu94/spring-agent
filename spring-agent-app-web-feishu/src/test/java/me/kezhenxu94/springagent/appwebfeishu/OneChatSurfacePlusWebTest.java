package me.kezhenxu94.springagent.appwebfeishu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.AgentRunRegistry;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.notify.Notifier;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuLongConnection;
import me.kezhenxu94.springagent.integration.websocket.run.WebRunListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * That two surfaces in one application is the arrangement this module means, and not the bug {@code
 * OneChatSurfaceTest} in {@code spring-agent-app-slack} describes.
 *
 * <p>That test says the rule: three singletons in this runtime answer for every run rather than for
 * one surface's runs, and a second surface breaks each of them in silence. This module is legal
 * because the *browser* is not a chat surface — it contributes no {@code Notifier} and no reply
 * format, and it claims a run by {@code chatType}, which no chat platform uses for its own. So the
 * three questions have one answer each here, and this asserts that they still do:
 *
 * <ul>
 *   <li>one {@code Notifier}, Feishu's, so {@code getIfAvailable()} resolves rather than throws;
 *   <li>one contributor filling {@code replyFormat}, and it says nothing about a browser's run —
 *       which is the assertion that would have caught the bug this module was written on top of;
 *   <li>each surface's listener declines the other's runs.
 * </ul>
 *
 * <p>A second <em>chat</em> surface here would still be the bug. Nothing in this file makes that
 * legal, and the first assertion is what notices.
 */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = WebFeishuTestProperties.ALL)
class OneChatSurfacePlusWebTest {

  /** Not started, so no long connection to Feishu is opened by a test. */
  @MockitoBean FeishuLongConnection feishuLongConnection;

  @Autowired ApplicationContext context;

  private static AgentRequest requestOf(final String chatType) {
    return AgentRequest.builder()
        .requestId("r-1")
        .scenario(BuiltInScenarios.CHAT)
        .userId("ou_1")
        .chatId("chat-1")
        .chatType(chatType)
        .conversationId("conv-1")
        .rootMessageId("conv-1")
        .replyMessageId("r-1")
        .userMessage(user -> user.text("hi"))
        .build();
  }

  @Test
  @DisplayName("exactly one notifier answers, so a report of a failed run has one place to go")
  void oneNotifier() {
    assertThat(context.getBeansOfType(Notifier.class)).hasSize(1);
    assertThat(context.getBean(Notifier.class).surface()).isEqualTo("feishu");
  }

  @Test
  @DisplayName("one contributor fills replyFormat, and only for the surface it is about")
  void oneReplyFormat() {
    final var contributors =
        context.getBeansOfType(PromptVariablesContributor.class).values().stream()
            .filter(it -> it.getClass().getName().contains("ReplyFormat"))
            .toList();
    assertThat(contributors).hasSize(1);

    // The bug this module could not have been built on top of. A contributor is a @Bean, so it is
    // asked about every run in this context — including the browser's, whose answers are rendered
    // as plain markdown. Answering there would put literal <at> and <text_tag> on the page.
    final var forWeb = contributors.getFirst().variables(requestOf(WebRunListener.CHAT_TYPE));
    assertThat(forWeb).doesNotContainKey("replyFormat");

    assertThat(contributors.getFirst().variables(requestOf("p2p"))).containsKey("replyFormat");
  }

  @Test
  @DisplayName("neither surface's listener claims the other's run")
  void theClaimsAreDisjoint() {
    final var listeners =
        context.getBeansOfType(AgentResponseListener.class).values().stream()
            .filter(
                it -> it.getClass().getName().startsWith("me.kezhenxu94.springagent.integration"))
            .toList();
    // Both are installed. Asserted, so that a listener quietly dropping out of the context makes
    // this test fail rather than pass for the wrong reason.
    assertThat(listeners).hasSize(2);

    // What a listener claims is what it attaches. Nothing is attached for somebody else's run, so
    // the count of attachments is the whole of the assertion — and it is checked in both
    // directions, since a claim being disjoint is a property of the pair rather than of either.
    for (final var listener : listeners) {
      final var feishuRun = new AgentRunRegistry(requestOf("p2p"));
      final var webRun = new AgentRunRegistry(requestOf(WebRunListener.CHAT_TYPE));
      listener.onStart(feishuRun);
      listener.onStart(webRun);
      final var claimedFeishu = !toolContextOf(feishuRun).isEmpty();
      final var claimedWeb = !toolContextOf(webRun).isEmpty();
      assertThat(claimedFeishu && claimedWeb)
          .as("%s claims both surfaces' runs", listener.getClass().getSimpleName())
          .isFalse();
    }
  }

  /**
   * What a listener attached, which is how "did it claim this run" is asked from outside. Both
   * listeners put their renderer in the tool context when they take a run on, and neither puts
   * anything there when it declines one.
   */
  private static Map<String, Object> toolContextOf(final AgentRunRegistry registry) {
    try {
      final var method = AgentRunRegistry.class.getDeclaredMethod("toolContext");
      method.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var context = (Map<String, Object>) method.invoke(registry);
      return context;
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
