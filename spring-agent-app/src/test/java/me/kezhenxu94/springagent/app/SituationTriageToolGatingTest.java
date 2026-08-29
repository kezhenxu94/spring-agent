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
  @DisplayName("a triage run gets no shell, and an ordinary chat run does")
  void shouldWithholdTheShellFromTriageRuns() {
    // A shell is the one tool where a successful prompt injection is indistinguishable from an
    // intrusion, and a triage run's input is written by whoever caused the event.
    assertThat(triageTools()).noneMatch(KubernetesShellTools.class::isInstance);
    assertThat(chatTools()).anyMatch(KubernetesShellTools.class::isInstance);
  }

  @Test
  @DisplayName("a triage run cannot schedule work or start subagents, and a chat run can")
  void shouldWithholdWorkThatOutlivesTheRun() {
    assertThat(triageTools()).noneMatch(ScheduledTaskTool.class::isInstance);
    assertThat(triageTools()).noneMatch(SubagentTools.class::isInstance);
    assertThat(chatTools()).anyMatch(ScheduledTaskTool.class::isInstance);
    assertThat(chatTools()).anyMatch(SubagentTools.class::isInstance);
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
