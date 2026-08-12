package me.kezhenxu94.springagent.service;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.JsonNode;

@Data
@Jacksonized
@Builder(toBuilder = true)
public class ValueRange {
  private String majorDimension;
  private String range;
  private Integer revision;
  private List<List<JsonNode>> values;
}
