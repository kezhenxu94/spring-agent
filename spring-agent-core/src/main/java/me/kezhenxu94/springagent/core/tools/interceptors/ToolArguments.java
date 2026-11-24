package me.kezhenxu94.springagent.core.tools.interceptors;

import java.util.ArrayList;
import java.util.List;
import me.kezhenxu94.springagent.core.tools.DisplayDescription;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What every tool call's arguments are put through before the tool sees them: the model's own
 * spellings corrected where the schema leaves no doubt, then checked against the schema.
 *
 * <p>In one place because there are three ways to call a tool — offered directly, through {@code
 * CallTool}, or by its own name though only the catalogue listed it — and the model makes the same
 * mistakes in all three.
 */
final class ToolArguments {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ToolArguments() {}

  /** {@code schema} parsed, or null where it will not parse — then nothing here applies. */
  static JsonNode schemaOf(final String schema) {
    try {
      return MAPPER.readTree(schema == null || schema.isBlank() ? "{}" : schema);
    } catch (JacksonException e) {
      return null;
    }
  }

  /**
   * Renames each {@code _p} in {@code arguments} to {@code p} where the schema declares {@code p}
   * and not {@code _p}, and the model did not also write {@code p}. A model copies the underscore
   * of {@code _display_description} onto the parameters beside it, and a tool asked for {@code
   * _decision} sees no {@code decision} at all and refuses — over a name nobody could mistake.
   *
   * @return whether anything was renamed
   */
  static boolean renameUnderscored(final ObjectNode arguments, final JsonNode schema) {
    if (schema == null || !(schema.get("properties") instanceof ObjectNode declared)) {
      return false;
    }
    var renamed = false;
    for (final var name : List.copyOf(arguments.propertyNames())) {
      if (name.length() < 2
          || name.charAt(0) != '_'
          || DisplayDescription.FIELD.equals(name)
          || declared.has(name)) {
        continue;
      }
      final var plain = name.substring(1);
      if (declared.has(plain) && !arguments.has(plain)) {
        arguments.set(plain, arguments.remove(name));
        renamed = true;
      }
    }
    return renamed;
  }

  /**
   * What is wrong with {@code arguments} for a tool of {@code schema}: each required property left
   * out, and each one the schema does not have — unless it allows others. A model that has not seen
   * the schema guesses at names, and a call made anyway reaches the tool with what it needs missing
   * and the guess ignored: a refusal that does not say why, or, worse, an empty query answered as
   * if it were one. The display description is the runtime's and never counts.
   */
  static List<String> mismatches(final ObjectNode arguments, final JsonNode schema) {
    if (schema == null) {
      return List.of();
    }
    final var problems = new ArrayList<String>();
    for (final var required : schema.path("required")) {
      final var property = required.asString("");
      if (!property.isEmpty()
          && !DisplayDescription.FIELD.equals(property)
          && !arguments.has(property)) {
        problems.add("missing required \"" + property + "\"");
      }
    }
    final var additional = schema.get("additionalProperties");
    final var open = additional != null && (additional.isObject() || additional.asBoolean(false));
    if (schema.get("properties") instanceof ObjectNode declared && !open) {
      for (final var name : arguments.propertyNames()) {
        if (!DisplayDescription.FIELD.equals(name) && !declared.has(name)) {
          problems.add("unknown \"" + name + "\"");
        }
      }
    }
    return problems;
  }

  /** {@code schema} on one line and without its {@code $schema}, as the model is shown it. */
  static String compact(final String schema) {
    try {
      final var root = MAPPER.readTree(schema);
      if (root instanceof ObjectNode object) {
        object.remove("$schema");
      }
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException e) {
      return schema;
    }
  }
}
