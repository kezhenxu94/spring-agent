package me.kezhenxu94.springagent.integration.feishu.model;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record ImageMessageContent(String imageKey) implements MessageContent {
  @Override
  public String getType() {
    return "image";
  }
}
