package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.Tools.AskUserQuestion;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AskUserQuestionTool.InvalidUserAnswerException;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question;
import org.springaicommunity.agent.tools.AskUserQuestionTool.Question.Option;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * How the ask is built follows from one fact: whether an answer can come back inside the call. Both
 * halves are easy to invert and neither fails loudly when they are — an ask that no longer ends the
 * turn just lets the model carry on, and validation switched the wrong way either rejects every
 * asynchronous ask or checks nothing at all.
 */
class AgentToolsProviderAskTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("an answer that only arrives later ends the turn and is not validated")
  void asynchronousAsk() throws Exception {
    final var composition = compose(true);

    final var ask = askCallback(composition);
    assertThat(ask.getToolMetadata().returnDirect()).isTrue();
    // Nothing came back, which is the whole point of the path: it must not be rejected for it.
    assertThatCode(() -> ask.call(toolInput())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an answer that arrives inside the call keeps the turn and is validated")
  void synchronousAsk() throws Exception {
    final var composition = compose(false);

    assertThat(
            Arrays.stream(composition.tools())
                .filter(ToolCallback.class::isInstance)
                .map(tool -> ((ToolCallback) tool).getToolDefinition().name()))
        .doesNotContain("AskUserQuestionTool");
    final var ask =
        Arrays.stream(composition.tools())
            .filter(AskUserQuestionTool.class::isInstance)
            .map(AskUserQuestionTool.class::cast)
            .findFirst()
            .orElseThrow();
    // An answer is expected here, so a question coming back without one is a fault worth raising.
    assertThatThrownBy(() -> ask.askUserQuestion(questions(), null))
        .isInstanceOf(InvalidUserAnswerException.class);
  }

  private AgentToolsProvider.AgentComposition compose(final boolean answersArriveLater)
      throws Exception {
    final var workspaces = mock(UserWorkspaceFactory.class);
    when(workspaces.forOwner("ou_1")).thenReturn(new UserHome(workspace));
    try (var context = new AnnotationConfigApplicationContext()) {
      context.refresh();
      final var provider =
          new AgentToolsProvider(
              workspaces,
              mock(McpServerConfigRepo.class),
              mock(McpClientFactory.class),
              context,
              properties());
      return provider.compose(
          AgentRequest.builder()
              .scenario(BuiltInScenarios.CHAT)
              .userId("ou_1")
              .chatId("oc_1")
              .userMessage(user -> user.text("Which database?"))
              .build(),
          Map.of(),
          todos -> {},
          // Answers nothing, as an asynchronous channel does and a broken synchronous one would.
          questions -> Map.of(),
          answersArriveLater);
    }
  }

  private static ToolCallback askCallback(final AgentToolsProvider.AgentComposition composition) {
    return Arrays.stream(composition.tools())
        .filter(ToolCallback.class::isInstance)
        .map(ToolCallback.class::cast)
        .filter(callback -> "AskUserQuestionTool".equals(callback.getToolDefinition().name()))
        .findFirst()
        .orElseThrow();
  }

  private static SpringAgentProperties properties() {
    return new SpringAgentProperties(
        null,
        new Ai(
            null,
            Set.of(),
            Map.of(),
            null,
            new Tools(new AskUserQuestion(true, null), null),
            "You are an agent.",
            null,
            null),
        Locale.ENGLISH);
  }

  private static List<Question> questions() {
    return List.of(
        new Question(
            "Which database should we use?",
            "Database",
            List.of(
                new Option("Postgres", "The one we already run"),
                new Option("MySQL", "The one we do not")),
            false));
  }

  private static String toolInput() {
    return """
    {"questions":[{"question":"Which database should we use?","header":"Database",\
    "options":[{"label":"Postgres","description":"The one we already run"},\
    {"label":"MySQL","description":"The one we do not"}],"multiSelect":false}]}\
    """;
  }
}
