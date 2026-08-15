package me.kezhenxu94.springagent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.shell.core.ShellRunner;
import org.springframework.test.context.TestPropertySource;

/**
 * That the command line starts, and that it starts as a command line: no server, no management
 * endpoints, no chat integration. The shape of {@code FeishuDisabledTest} in spring-agent-app.
 *
 * <p>The shell runner does run here, since Boot invokes {@code ApplicationRunner}s in a test
 * context too. The test JVM's stdin is empty, so it takes the end-of-file as the user leaving — the
 * same path Ctrl-D takes.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Nothing here calls a model; these only have to resolve, since application.yaml declares
      // them as placeholders with no defaults.
      "spring.ai.openai.base-url=http://localhost:1",
      "spring.ai.openai.api-key=test",
      "spring.ai.openai.chat.model=test-model",
      "spring.ai.openai.embedding.base-url=http://localhost:1",
      "spring.ai.openai.embedding.api-key=test",
      "spring.ai.openai.embedding.model=test-embedding",
      // A database of its own per run, rather than the developer's real one under ~/.spring-agent.
      "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/spring-agent-cli-test.db",
      "app.ai.tools.shell.type=none"
    })
class SpringAgentCliApplicationTests {

  @Autowired ApplicationContext context;

  @Test
  void startsWithoutAWebServer() {
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("webserver"))
        .isEmpty();
    assertThat(context.containsBean("dispatcherServlet")).isFalse();
  }

  @Test
  void hasNoManagementEndpoints() {
    // No actuator starter is declared; this notices one arriving transitively, which is how a
    // command line quietly grows an HTTP port.
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("actuator") || name.contains("Endpoint"))
        .isEmpty();
  }

  @Test
  void hasNoChatIntegration() {
    assertThat(context.getBeanNamesForType(Object.class))
        .filteredOn(name -> name.toLowerCase().contains("feishu"))
        .isEmpty();
  }

  @Test
  void runsItsOwnShellRunner() {
    // Both runners exist and @Primary is what decides. Should that stop being true, the command
    // line silently becomes a command shell that cannot take a question.
    assertThat(context.getBean(ShellRunner.class)).isInstanceOf(CliShellRunner.class);
  }
}
