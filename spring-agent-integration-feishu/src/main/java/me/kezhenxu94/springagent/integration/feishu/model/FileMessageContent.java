package me.kezhenxu94.springagent.integration.feishu.model;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Data
@Builder
@Jacksonized
public class FileMessageContent implements MessageContent {
  private String fileKey;
  private String fileName;

  @Override
  public String getType() {
    return "file";
  }
}
