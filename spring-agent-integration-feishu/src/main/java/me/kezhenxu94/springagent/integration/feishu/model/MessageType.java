package me.kezhenxu94.springagent.integration.feishu.model;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum MessageType {
  TEXT("text"),
  POST("post"),
  IMAGE("image"),
  FILE("file"),
  AUDIO("audio"),
  MEDIA("media"),
  STICKER("sticker"),
  INTERACTIVE("interactive"),
  SHARE_CHAT("share_chat"),
  SHARE_USER("share_user");

  @JsonValue private final String value;

  @Override
  public String toString() {
    return value;
  }
}
