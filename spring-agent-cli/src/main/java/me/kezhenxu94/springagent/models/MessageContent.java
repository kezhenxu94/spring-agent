package me.kezhenxu94.springagent.models;

import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = MessageContentDeserializer.class)
public interface MessageContent {
  String getType();
}
