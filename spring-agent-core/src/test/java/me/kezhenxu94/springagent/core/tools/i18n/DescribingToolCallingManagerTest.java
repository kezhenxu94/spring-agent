package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.json.JsonMapper;

class DescribingToolCallingManagerTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

  private static ToolDefinition definition(final String schema) {
    return ToolDefinition.builder()
        .name("Read")
        .description("Read a file")
        .inputSchema(schema)
        .build();
  }

  private static List<ToolDefinition> resolve(final ToolDefinition given, final Locale locale) {
    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(List.of(given));
    return new DescribingToolCallingManager(delegate, messages(locale))
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class));
  }

  @Test
  @DisplayName("the tool the model is offered carries the display parameter")
  void offersTheParameter() {
    final var described = resolve(definition(SCHEMA), Locale.ENGLISH).getFirst();

    final var added =
        MAPPER.readTree(described.inputSchema()).get("properties").get(DisplayDescription.FIELD);
    assertThat(added.get("description").asString())
        .as("what the parameter is for, told to the model in the workspace's language")
        .isEqualTo(messages(Locale.ENGLISH).get(DescribingToolCallingManager.DESCRIPTION_KEY))
        .isNotEqualTo(DescribingToolCallingManager.DESCRIPTION_KEY);
    assertThat(described.name()).isEqualTo("Read");
    assertThat(described.description())
        .as("the tool search fingerprints these two, so neither may move")
        .isEqualTo("Read a file");
  }

  @Test
  @DisplayName("the parameter is asked for in the workspace's language")
  void localized() {
    final var chinese = resolve(definition(SCHEMA), Locale.SIMPLIFIED_CHINESE).getFirst();

    assertThat(
            MAPPER
                .readTree(chinese.inputSchema())
                .get("properties")
                .get(DisplayDescription.FIELD)
                .get("description")
                .asString())
        .isEqualTo(
            messages(Locale.SIMPLIFIED_CHINESE).get(DescribingToolCallingManager.DESCRIPTION_KEY))
        .isNotEqualTo(messages(Locale.ENGLISH).get(DescribingToolCallingManager.DESCRIPTION_KEY));
  }

  /** Identity, and a contract: a rebuild for nothing is an allocation per tool per iteration. */
  @Test
  @DisplayName("a tool there is nothing to add to comes back as the very object that went in")
  void untouchedIsTheSameObject() {
    final var given = definition("{}");

    assertThat(resolve(given, Locale.ENGLISH)).first().isSameAs(given);
  }

  private static CoreMessages messages(final Locale locale) {
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(source, new SpringAgentProperties(null, null, locale, null, null));
  }
}
