package me.kezhenxu94.springagent.models;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record TextMessageContent(String text) implements MessageContent {
  @Override
  public String getType() {
    return "text";
  }
}
