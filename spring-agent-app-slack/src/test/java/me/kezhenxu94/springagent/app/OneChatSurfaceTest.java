package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.agent.AgentResponseListener;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.notify.Notifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * That this application carries exactly one chat surface.
 *
 * <p>Not a style rule. Three singletons in this runtime answer for every run rather than for the
 * runs of one surface, and a second surface on the classpath breaks each of them quietly:
 *
 * <ul>
 *   <li>a {@code @Bean AgentResponseListener} claims every run, so Feishu's would try to reply a
 *       card onto a Slack timestamp, fail, and abort the run — every Slack turn dying before it
 *       reached the model;
 *   <li>{@code PromptVariablesContributor}s are merged with {@code putAll} in declaration order, so
 *       whichever surface is registered last tells the model how to format an answer for the other
 *       one's chat;
 *   <li>{@code SituationSweeper} resolves its notifier with {@code getIfAvailable()}, which throws
 *       outright when two are present, so a failed triage run reports nothing.
 * </ul>
 *
 * <p>None of the three fails at startup, which is why this test exists: the shape of the mistake is
 * a build that passes and a deployment that misbehaves. If this ever needs to become legal, that is
 * a core change — a discriminator on the request — and not a matter of relaxing the assertion.
 */
@org.springframework.context.annotation.Import(AbstractIntegrationTest.SlackStub.class)
@SpringBootTest
class OneChatSurfaceTest extends AbstractIntegrationTest {

  /** Where a chat surface's beans live, whichever surface it is. */
  private static final String INTEGRATION_PACKAGE = "me.kezhenxu94.springagent.integration";

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("only the Slack surface is installed, and only one notifier answers for it")
  void carriesExactlyOneChatSurface() {
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("feishu"))
        .isEmpty();

    assertThat(context.getBeansOfType(Notifier.class)).hasSize(1);

    assertThat(context.getBeansOfType(PromptVariablesContributor.class).values())
        .filteredOn(contributor -> contributor.getClass().getName().contains("ReplyFormat"))
        .hasSize(1);

    // At most one, not exactly one. The question this test asks is whether two surfaces are both
    // claiming every run; whether the installed surface registered its listener at all is a
    // different question, and one its own module's tests answer. Asserting "exactly one" here
    // would make this test fail for a reason it is not about.
    assertThat(context.getBeansOfType(AgentResponseListener.class).values())
        .filteredOn(listener -> listener.getClass().getName().startsWith(INTEGRATION_PACKAGE))
        .hasSizeLessThanOrEqualTo(1);
  }
}
