package me.kezhenxu94.springagent.models;

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
