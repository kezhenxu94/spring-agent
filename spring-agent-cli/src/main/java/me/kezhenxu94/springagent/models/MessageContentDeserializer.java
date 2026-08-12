package me.kezhenxu94.springagent.models;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class MessageContentDeserializer extends ValueDeserializer<MessageContent> {
  @Override
  public MessageContent deserialize(JsonParser p, DeserializationContext ctxt) {
    JsonNode node = ctxt.readTree(p);

    // Check for text message
    if (node.has("text")) {
      return TextMessageContent.builder().text(node.get("text").asString()).build();
    }

    // Check for post message
    if (node.has("title") && node.has("content")) {
      List<List<PostMessageContent.ContentElement>> content = new ArrayList<>();
      node.get("content")
          .forEach(
              row -> {
                List<PostMessageContent.ContentElement> elements = new ArrayList<>();
                row.forEach(
                    element -> {
                      final var builder =
                          PostMessageContent.ContentElement.builder()
                              .tag(element.get("tag").asString());

                      if (element.has("text")) builder.text(element.get("text").asString());
                      if (element.has("href")) builder.href(element.get("href").asString());
                      if (element.has("user_id")) builder.userId(element.get("user_id").asString());
                      if (element.has("user_name"))
                        builder.userName(element.get("user_name").asString());
                      if (element.has("image_key"))
                        builder.imageKey(element.get("image_key").asString());
                      if (element.has("file_key"))
                        builder.fileKey(element.get("file_key").asString());
                      if (element.has("emoji_type"))
                        builder.emojiType(element.get("emoji_type").asString());
                      if (element.has("language"))
                        builder.language(element.get("language").asString());
                      if (element.has("style")) {
                        List<String> styles = new ArrayList<>();
                        element.get("style").forEach(style -> styles.add(style.asString()));
                        builder.style(styles);
                      }

                      elements.add(builder.build());
                    });
                content.add(elements);
              });

      return PostMessageContent.builder()
          .title(node.get("title").asString())
          .content(content)
          .build();
    }

    // Check for image message
    if (node.has("image_key") && !node.has("file_key")) {
      return ImageMessageContent.builder().imageKey(node.get("image_key").asString()).build();
    }

    // Check for file message
    if (node.has("file_key") && node.has("file_name")) {
      return FileMessageContent.builder()
          .fileKey(node.get("file_key").asString())
          .fileName(node.get("file_name").asString())
          .build();
    }

    // Check for audio message
    if (node.has("file_key") && node.has("duration")) {
      return AudioMessageContent.builder()
          .fileKey(node.get("file_key").asString())
          .duration(node.get("duration").asInt())
          .build();
    }

    // Check for media message
    if (node.has("file_key") && node.has("image_key")) {
      return MediaMessageContent.builder()
          .fileKey(node.get("file_key").asString())
          .imageKey(node.get("image_key").asString())
          .build();
    }

    // Default to file message if only file_key is present
    if (node.has("file_key")) {
      return FileMessageContent.builder().fileKey(node.get("file_key").asString()).build();
    }

    throw DatabindException.from(p, "Unknown message content type");
  }
}
