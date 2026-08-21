package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class SpringAgentApplicationTests extends AbstractIntegrationTest {

  @Autowired SpringAgentProperties properties;
  @Autowired Environment environment;

  @Test
  void contextLoads() {}

  @Test
  @DisplayName("the tool index key core defaults reaches the application without any configuration")
  void toolIndexKeyIsDefaulted() {
    // Nothing here sets it, so a registration core got wrong leaves the advisor reading the
    // conversation id and re-embedding every tool description per thread — a cost, not a failure,
    // which is why no run would complain.
    assertThat(
            environment.getProperty(
                "spring.ai.chat.client.tool-search-advisor.session-id-key-name"))
        .isEqualTo(SpringAgent.TOOL_INDEX_KEY);
  }

  @Test
  @DisplayName(
      "the shipped scheduled-task prompt renders, which nothing else would find out until a task"
          + " fires")
  void scheduledTaskPromptRenders() {
    final var rendered =
        PromptTemplate.builder()
            .template(properties.ai().scheduledTaskPrompt())
            .variables(Map.of("taskText", "summarise the thread"))
            .build()
            .render();

    assertThat(rendered).contains("summarise the thread").doesNotContain("{taskText}");
  }

  @Test
  @DisplayName("the shipped system prompt renders against exactly the variables a run supplies")
  void systemPromptRenders() {
    // SystemPromptTemplate fails on any {name} it was not given a value for, and this is only
    // reached when a message arrives — so a stray brace in the prompt breaks every chat, at
    // runtime, with nothing at startup having complained.
    final var rendered =
        new SystemPromptTemplate(properties.ai().systemPrompt())
            .render(
                Map.of(
                    "userId", "ou_1",
                    "chatId", "oc_1",
                    "chatType", "p2p",
                    "threadId", "",
                    "parentId", "",
                    "mentions", "none",
                    // The slot the surface receiving the answer fills, empty as core defaults it:
                    // a prompt that names it and a run that never supplies it is the combination
                    // that would fail every chat.
                    "replyFormat", ""));

    assertThat(rendered).contains("ou_1", "oc_1").doesNotContain("{replyFormat}");
    // The permission rules name the tool exactly as it is registered; a near miss reads fine to a
    // human and leaves the model calling something that does not exist.
    assertThat(rendered).contains("AskUserQuestionTool");
  }

  @Test
  @DisplayName("app.ai.tools binds despite holding shell settings this record does not declare")
  void askUserQuestionSettingsBind() {
    // The same prefix carries app.ai.tools.shell.type, which ShellBackendResolver reads on its own
    // and Tools does not declare. Were that to fail binding rather than be ignored, the whole
    // record would fall back to its defaults and read exactly like a correct binding.
    final var askUserQuestion = properties.ai().tools().askUserQuestion();
    assertThat(askUserQuestion.enabled()).isTrue();
    assertThat(askUserQuestion.ttl()).isEqualTo(Duration.ofHours(24));
  }

  @Nested
  @SpringBootTest(
      properties = {
        "app.ai.tools.ask-user-question.enabled=false",
        "app.ai.tools.ask-user-question.ttl=30m"
      })
  class Overridden {

    @Autowired SpringAgentProperties overridden;

    @Test
    @DisplayName("a deployment can turn the tool off and shorten how long questions stay open")
    void overridesReachTheRecord() {
      assertThat(overridden.ai().tools().askUserQuestion().enabled()).isFalse();
      assertThat(overridden.ai().tools().askUserQuestion().ttl()).isEqualTo(Duration.ofMinutes(30));
    }
  }
}
