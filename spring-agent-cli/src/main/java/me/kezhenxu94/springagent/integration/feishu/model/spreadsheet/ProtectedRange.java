package me.kezhenxu94.springagent.integration.feishu.model.spreadsheet;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Builder(toBuilder = true)
public record ProtectedRange(
    String protectId, String sheetId, String lockInfo, Dimension dimension, Editors editors) {

  @Jacksonized
  @Builder(toBuilder = true)
  public record Dimension(String sheetId, int startIndex, int endIndex, String majorDimension) {}

  @Jacksonized
  @Builder(toBuilder = true)
  public record Editors(List<User> users) {}

  @Jacksonized
  @Builder(toBuilder = true)
  public record User(String memberType, String memberId) {}
}
