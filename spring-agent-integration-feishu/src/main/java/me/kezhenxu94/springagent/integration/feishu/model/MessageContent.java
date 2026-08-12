package me.kezhenxu94.springagent.integration.feishu.model;

import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = MessageContentDeserializer.class)
public interface MessageContent {
  String getType();
}
