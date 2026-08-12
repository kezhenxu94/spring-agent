package me.kezhenxu94.springagent.integration.feishu.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ReceiveIdType {
  OPEN_ID("open_id"),
  USER_ID("user_id"),
  UNION_ID("union_id"),
  EMAIL("email"),
  CHAT_ID("chat_id");

  @JsonValue private final String value;

  @Override
  public String toString() {
    return value;
  }
}
