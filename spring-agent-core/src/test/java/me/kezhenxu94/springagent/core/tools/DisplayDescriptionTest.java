package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DisplayDescriptionTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String ASKED = "What this call does";

  private static final String SCHEMA =
      """
      {
        "$schema" : "https://json-schema.org/draft/2020-12/schema",
        "type" : "object",
        "properties" : {
          "path" : {
            "type" : "string",
            "description" : "Absolute path to read"
          }
        },
        "required" : [ "path" ],
        "additionalProperties" : false
      }\
      """;

  private static String inject(final String schema) {
    return DisplayDescription.inject("Read", schema, ASKED);
  }

  @Test
  @DisplayName("the parameter is added as a required string, and nothing else in the schema moves")
  void injects() {
    final var before = MAPPER.readTree(SCHEMA);
    final var after = MAPPER.readTree(inject(SCHEMA));

    final var added = after.get("properties").get(DisplayDescription.FIELD);
    assertThat(added.get("type").asString()).isEqualTo("string");
    assertThat(added.get("description").asString()).isEqualTo(ASKED);
    assertThat(after.get("required").valueStream().map(JsonNode::asString))
        .as("required, so every call has a title, and the tool's own stay required")
        .containsExactly("path", DisplayDescription.FIELD);
    assertThat(after.get("properties").get("path")).isEqualTo(before.get("properties").get("path"));
    assertThat(after.get("$schema")).isEqualTo(before.get("$schema"));
    assertThat(after.get("additionalProperties")).isEqualTo(before.get("additionalProperties"));
    assertThat(List.copyOf(after.propertyNames()))
        .as("the schema's keys keep the order the generator put them in")
        .isEqualTo(List.copyOf(before.propertyNames()));
  }

  /**
   * Identity, not equality: every tool of every MCP server a user has registered arrives here on
   * every iteration of every request.
   */
  @Test
  @DisplayName("a tool that asks for a description of its own comes back as the very string given")
  void ownDescriptionWins() {
    final var schema =
        """
        {"type":"object","properties":{"command":{"type":"string"},\
        "description":{"type":"string"}}}\
        """;

    assertThat(inject(schema)).isSameAs(schema);
  }

  @Test
  @DisplayName("a schema listing nothing as required gets the list it needs to say this is")
  void requiredListIsAdded() {
    final var schema = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

    assertThat(
            MAPPER.readTree(inject(schema)).get("required").valueStream().map(JsonNode::asString))
        .containsExactly(DisplayDescription.FIELD);
  }

  /** A schema that malformed is not one to be repaired here; the parameter is still offered. */
  @Test
  @DisplayName("a required that is not a list is left where it is")
  void malformedRequired() {
    final var schema =
        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":\"path\"}";
    final var after = MAPPER.readTree(inject(schema));

    assertThat(after.get("required").asString()).isEqualTo("path");
    assertThat(after.get("properties").has(DisplayDescription.FIELD)).isTrue();
  }

  @Test
  @DisplayName("injecting twice adds the parameter once")
  void idempotent() {
    final var once = inject(SCHEMA);
    assertThat(inject(once)).isSameAs(once);
  }

  @Test
  @DisplayName("a schema with no parameters to add to, or no schema at all, is left as it is")
  void oddSchemas() {
    for (final var schema : List.of("{}", "not json at all", "[]", "")) {
      assertThat(inject(schema)).as(schema).isSameAs(schema);
    }
    assertThat(inject(null)).isNull();
  }

  /**
   * An open schema's arguments are a payload rather than a parameter list, so a field added there
   * means something to whatever reads that payload — and would be stripped back off before it got
   * there.
   */
  @Test
  @DisplayName("a tool that takes arbitrary JSON is not asked for a description")
  void openSchema() {
    final var schema =
        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},"
            + "\"additionalProperties\":true}";

    assertThat(inject(schema)).isSameAs(schema);
  }

  @Test
  @DisplayName("the field is taken off the arguments and the tool's own are left alone")
  void strips() {
    final var stripped =
        DisplayDescription.strip(
            "{\"path\":\"/etc/hosts\",\""
                + DisplayDescription.FIELD
                + "\":\"Read the hosts file\"}");

    assertThat(MAPPER.readTree(stripped)).isEqualTo(MAPPER.readTree("{\"path\":\"/etc/hosts\"}"));
  }

  @Test
  @DisplayName("a tool's own description argument survives the strip")
  void keepsTheToolsOwn() {
    final var arguments = "{\"command\":\"ls\",\"description\":\"List the directory\"}";

    assertThat(DisplayDescription.strip(arguments)).isSameAs(arguments);
  }

  @Test
  @DisplayName("arguments that are not a JSON object are handed on untouched")
  void oddArguments() {
    for (final var arguments : List.of("{}", "not json at all", "[]", "")) {
      assertThat(DisplayDescription.strip(arguments)).as(arguments).isSameAs(arguments);
    }
    assertThat(DisplayDescription.strip(null)).isNull();
  }

  /**
   * The field's name inside a value the model wrote, rather than as a key of its own. Stripping by
   * text would have taken it out of the argument the tool was meant to receive.
   */
  @Test
  @DisplayName("the field's name written as a value is not mistaken for the field")
  void nameInsideAValue() {
    final var arguments = "{\"pattern\":\"" + DisplayDescription.FIELD + "\"}";

    assertThat(MAPPER.readTree(DisplayDescription.strip(arguments)))
        .isEqualTo(MAPPER.readTree(arguments));
  }
}
