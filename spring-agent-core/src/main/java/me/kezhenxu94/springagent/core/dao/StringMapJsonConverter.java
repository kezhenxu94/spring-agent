package me.kezhenxu94.springagent.core.dao;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a string map as a JSON column under JPA, for maps that no query ever looks inside.
 *
 * <p>Its own mapper rather than the application's: a converter is instantiated by Hibernate outside
 * the application context, and this mapping must not drift with whatever modules or configuration
 * the shared bean picks up.
 */
@Converter
public class StringMapJsonConverter implements AttributeConverter<Map<String, String>, String> {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {};

  @Override
  public String convertToDatabaseColumn(final Map<String, String> attribute) {
    // null rather than "{}" so an absent map reads back absent, matching the MongoDB backend.
    return attribute == null ? null : MAPPER.writeValueAsString(attribute);
  }

  @Override
  public Map<String, String> convertToEntityAttribute(final String column) {
    return column == null || column.isBlank() ? null : MAPPER.readValue(column, TYPE);
  }
}
