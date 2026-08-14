package me.kezhenxu94.springagent.core.aot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * These assert what a native image needs, and every one of them stands for a failure that only
 * appeared at run time — the image built cleanly each time.
 */
class AgentToolsRuntimeHintsTest {

  private final RuntimeHints hints = new RuntimeHints();

  @BeforeEach
  void register() {
    new AgentToolsRuntimeHints().registerHints(hints, getClass().getClassLoader());
  }

  @Test
  void registersTheToolClassesThemselves() {
    // Without method metadata Spring AI finds no @Tool on the class, and ChatClient.tools() rejects
    // an object with none — so the run fails rather than the tool quietly going missing.
    assertThat(RuntimeHintsPredicates.reflection().onType(AutoMemoryTools.class)).accepts(hints);
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(TodoWriteTool.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS))
        .accepts(hints);
  }

  @Test
  void reachesRecordsNestedMoreThanOneLevelDeep() {
    // Option is nested inside Question, which is nested inside the tool. One level of
    // getDeclaredClasses() missed it, and a record whose components are unregistered cannot be read
    // in a native image at all: UnsupportedFeatureError, and the tool call fails with it.
    assertThat(RuntimeHintsPredicates.reflection().onType(AskUserQuestionTool.Question.class))
        .accepts(hints);
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(AskUserQuestionTool.Question.Option.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS))
        .accepts(hints);
  }

  @Test
  void registersTheToolSearchAdvisorsPromptResource() {
    // Read while the advisor is being built, so its absence takes the whole run with it.
    assertThat(RuntimeHintsPredicates.resource().forResource("DEFAULT_SYSTEM_PROMPT_SUFFIX.md"))
        .accepts(hints);
  }
}
