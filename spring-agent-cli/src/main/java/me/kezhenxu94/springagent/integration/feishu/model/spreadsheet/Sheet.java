package me.kezhenxu94.springagent.integration.feishu.model.spreadsheet;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Jacksonized
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Sheet(
    String sheetId,
    String title,
    int index,
    boolean hidden,
    GridProperties gridProperties,
    String resourceType,
    List<Merge> merges) {

  @Jacksonized
  @Builder(toBuilder = true)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record GridProperties(
      int rowCount, int columnCount, int frozenRowCount, int frozenColumnCount) {}

  @Jacksonized
  @Builder(toBuilder = true)
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Merge(
      int startRowIndex, int endRowIndex, int startColumnIndex, int endColumnIndex) {}
}
