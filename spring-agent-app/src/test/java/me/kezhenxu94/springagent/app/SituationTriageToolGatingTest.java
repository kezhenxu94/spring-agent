package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.core.tools.ScheduledTaskTool;
import me.kezhenxu94.springagent.core.tools.SubagentTools;
import me.kezhenxu94.springagent.events.situation.SituationTriageScenario;
import me.kezhenxu94.springagent.events.tools.SituationTools;
import me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * What a triage run is actually offered, resolved against the real set of {@code @AgentTool} beans
 * in a context that has every optional module on its classpath.
 *
 * <p>Here rather than beside the scenario because this is the only place the question can honestly
 * be asked. The scenario withholds the two remote shells by class name — naming their types would
 * make the events module depend on both optional modules — so a unit test there can assert the
 * intent but not the outcome. This application depends on both, so the beans exist and the answer
 * is real.
 *
 * <p>The CHAT half of each assertion is the control. Without it a scenario that withheld
 * everything, or a context where the shell beans were simply absent, would pass just as well and
 * prove nothing.
 */
@SpringBootTest
@TestPropertySource(properties = {"app.events.enabled=true"})
class SituationTriageToolGatingTest extends AbstractIntegrationTest {

  @Autowired AgentToolsProvider agentToolsProvider;

  @Test
  @DisplayName("a triage run cannot schedule work, and an ordinary chat run can")
  void shouldWithholdTheSchedulerFromTriageRuns() {
    // The one gate, and the CHAT half is the control: without it a scenario that withheld nothing
    // at
    // all, or a context where the bean was simply absent, would pass just as well.
    assertThat(triageTools()).noneMatch(ScheduledTaskTool.class::isInstance);
    assertThat(chatTools()).anyMatch(ScheduledTaskTool.class::isInstance);
  }

  @Test
  @DisplayName("it gets everything else a chat run does, resolved against the real beans")
  void shouldOfferEverythingElse() {
    // Including the shell, where a deployment turned one on. Withholding it would close nothing —
    // every MCP callback reaches these runs whatever the scenario says — while leaving the agent
    // unable to read a log about the alert it is triaging.
    assertThat(triageTools()).anyMatch(KubernetesShellTools.class::isInstance);
    assertThat(triageTools()).anyMatch(SubagentTools.class::isInstance);

    final var withheld =
        chatTools().stream().filter(tool -> !triageTools().contains(tool)).toList();
    assertThat(withheld).singleElement().isInstanceOf(ScheduledTaskTool.class);
  }

  @Test
  @DisplayName("it does get the situation tools, or it could conclude nothing")
  void shouldOfferTheSituationTools() {
    assertThat(triageTools()).anyMatch(SituationTools.class::isInstance);
    // And a person in a chat can ask what is being watched, which is why these are ordinary
    // @AgentTool beans rather than something only a triage run sees.
    assertThat(chatTools()).anyMatch(SituationTools.class::isInstance);
  }

  private java.util.List<Object> triageTools() {
    return agentToolsProvider.resolveScenarioTools(new SituationTriageScenario());
  }

  private java.util.List<Object> chatTools() {
    return agentToolsProvider.resolveScenarioTools(BuiltInScenarios.CHAT);
  }
}
