package me.kezhenxu94.springagent.core.tools.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class LocalizingToolCallingManagerTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String SCHEMA =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "type" : "object",
        "properties" : {
          "path" : {
            "type" : "string",
            "description" : "Absolute path to read"
          },
          "limit" : {
            "type" : "integer",
            "description" : "How many lines"
          }
        },
        "required" : [ "path" ],
        "additionalProperties" : false
      }\
      """;

  /** A source that answers from two maps, so a test says exactly what is translated. */
  private static ToolTexts texts(
      final Map<String, String> descriptions, final Map<String, String> parameters) {
    return new ToolTexts() {
      @Override
      public String description(final String toolName) {
        return descriptions.get(toolName);
      }

      @Override
      public String parameter(final String toolName, final String parameterName) {
        return parameters.get(toolName + "." + parameterName);
      }

      @Override
      public boolean covers(final String toolName) {
        return descriptions.containsKey(toolName)
            || parameters.keySet().stream().anyMatch(key -> key.startsWith(toolName + "."));
      }
    };
  }

  private static ToolDefinition definition() {
    return ToolDefinition.builder()
        .name("Read")
        .description("Read a file")
        .inputSchema(SCHEMA)
        .build();
  }

  private static List<ToolDefinition> resolve(
      final ToolDefinition given, final List<ToolTexts> texts) {
    final var delegate = mock(ToolCallingManager.class);
    when(delegate.resolveToolDefinitions(any())).thenReturn(List.of(given));
    return new LocalizingToolCallingManager(delegate, texts)
        .resolveToolDefinitions(mock(ToolCallingChatOptions.class));
  }

  /**
   * Identity, not equality, and it is a contract rather than an optimisation: every tool of every
   * MCP server a user has registered arrives here on every request, and the tool search re-indexes
   * the whole set when the descriptions it is handed stop hashing the same.
   */
  @Test
  @DisplayName("a tool nothing translates comes back as the very object that went in")
  void untranslatedIsUntouched() {
    final var given = definition();
    assertThat(resolve(given, List.of(texts(Map.of(), Map.of())))).first().isSameAs(given);
  }

  @Test
  @DisplayName("no sources at all is the same, rather than a rebuild that changes nothing")
  void noSources() {
    final var given = definition();
    assertThat(resolve(given, List.of())).first().isSameAs(given);
  }

  @Test
  @DisplayName("the description is replaced and the name is left alone")
  void toolDescription() {
    final var localized =
        resolve(definition(), List.of(texts(Map.of("Read", "读取一个文件"), Map.of()))).getFirst();

    assertThat(localized.name()).isEqualTo("Read");
    assertThat(localized.description()).isEqualTo("读取一个文件");
    assertThat(localized.inputSchema())
        .as("nothing about the parameters was translated")
        .isEqualTo(SCHEMA);
  }

  @Test
  @DisplayName("a parameter's description is replaced inside the schema, and nothing else moves")
  void parameterDescription() {
    final var localized =
        resolve(definition(), List.of(texts(Map.of(), Map.of("Read.path", "要读取的绝对路径")))).getFirst();

    final var before = MAPPER.readTree(SCHEMA);
    final var after = MAPPER.readTree(localized.inputSchema());

    assertThat(after.get("properties").get("path").get("description").asString())
        .isEqualTo("要读取的绝对路径");
    assertThat(after.get("properties").get("limit").get("description").asString())
        .as("an untranslated parameter keeps what it declares")
        .isEqualTo("How many lines");
    assertThat(after.get("properties").get("path").get("type").asString()).isEqualTo("string");
    assertThat(after.get("$schema")).isEqualTo(before.get("$schema"));
    assertThat(after.get("required")).isEqualTo(before.get("required"));
    assertThat(after.get("additionalProperties")).isEqualTo(before.get("additionalProperties"));
    assertThat(List.copyOf(after.propertyNames()))
        .as("the schema's keys keep the order the generator put them in")
        .isEqualTo(List.copyOf(before.propertyNames()));
    assertThat(localized.description())
        .as("nothing translated the tool itself")
        .isEqualTo("Read a file");
  }

  @Test
  @DisplayName("a key naming a parameter the tool does not take changes nothing and does not throw")
  void unknownParameter() {
    final var given = definition();
    final var localized =
        resolve(given, List.of(texts(Map.of(), Map.of("Read.nosuch", "nothing")))).getFirst();

    assertThat(localized.inputSchema()).isEqualTo(SCHEMA);
    assertThat(localized).as("nothing changed, so nothing was rebuilt").isSameAs(given);
  }

  @Test
  @DisplayName(
      "a schema that is not an object, or not JSON at all, costs the translation not the tool")
  void oddSchemas() {
    for (final var schema : List.of("{}", "not json at all", "[]")) {
      final var given =
          ToolDefinition.builder()
              .name("Read")
              .description("Read a file")
              .inputSchema(schema)
              .build();
      final var localized =
          resolve(given, List.of(texts(Map.of("Read", "读取"), Map.of("Read.path", "路径"))))
              .getFirst();

      assertThat(localized.inputSchema()).as(schema).isEqualTo(schema);
      assertThat(localized.description()).as(schema).isEqualTo("读取");
    }
  }

  @Test
  @DisplayName("the first source that covers a tool answers for it")
  void firstSourceWins() {
    final var localized =
        resolve(
                definition(),
                List.of(
                    texts(Map.of(), Map.of()),
                    texts(Map.of("Read", "第一个"), Map.of()),
                    texts(Map.of("Read", "第二个"), Map.of())))
            .getFirst();

    assertThat(localized.description()).isEqualTo("第一个");
  }

  /**
   * The direct regression test for a re-index storm. The tool search hashes the names and
   * descriptions it is given and clears and re-embeds the whole index when that hash moves, so
   * localizing has to be a pure function of what went in.
   */
  @Test
  @DisplayName("localizing the same tool twice, from two instances, gives the same text both times")
  void isDeterministic() {
    final var sources = List.of(texts(Map.of("Read", "读取"), Map.of("Read.path", "路径")));
    final var once = resolve(definition(), sources).getFirst();
    final var twice = resolve(definition(), sources).getFirst();

    assertThat(twice.description()).isEqualTo(once.description());
    assertThat(twice.inputSchema()).isEqualTo(once.inputSchema());
    assertThat(twice.name()).isEqualTo(once.name());
  }
}
