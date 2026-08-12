package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringAgentApplicationTests extends AbstractIntegrationTest {

  @Autowired SpringAgentProperties properties;

  @Test
  void contextLoads() {}

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
}
