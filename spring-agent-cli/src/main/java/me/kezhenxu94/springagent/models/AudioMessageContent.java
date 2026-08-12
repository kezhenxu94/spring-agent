package me.kezhenxu94.springagent.models;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record AudioMessageContent(String fileKey, Integer duration) implements MessageContent {
  @Override
  public String getType() {
    return "audio";
  }
}
