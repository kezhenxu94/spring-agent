package me.kezhenxu94.springagent.core.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parameter descriptions carried by a tool definition's input schema.
 *
 * <p>Only the tool's own parameters, at the top level of {@code properties}, which is exactly what
 * {@code LocalizingToolCallingManager} rewrites — so this reads back what that writes, and a test
 * over it cannot pass by finding text nothing translates.
 */
public final class SchemaDescriptions {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private SchemaDescriptions() {}

  /** Each parameter with a description, by name; a parameter without one is not listed. */
  public static Map<String, String> of(final String schema) {
    final var found = new LinkedHashMap<String, String>();
    if (!(MAPPER.readTree(schema) instanceof ObjectNode root)
        || !(root.get("properties") instanceof ObjectNode properties)) {
      return found;
    }
    for (final var name : properties.propertyNames()) {
      if (properties.get(name) instanceof ObjectNode property
          && property.get("description") != null) {
        found.put(name, property.get("description").asString());
      }
    }
    return found;
  }
}
