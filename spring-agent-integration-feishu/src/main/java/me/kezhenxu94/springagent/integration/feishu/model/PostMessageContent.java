package me.kezhenxu94.springagent.integration.feishu.model;

import java.util.List;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

@Builder
@Jacksonized
public record PostMessageContent(String title, List<List<ContentElement>> content)
    implements MessageContent {

  @Override
  public String getType() {
    return "post";
  }

  @Builder
  @Jacksonized
  public static record ContentElement(
      String tag,
      String text,
      String href,
      String userId,
      String userName,
      String imageKey,
      String fileKey,
      String emojiType,
      String language,
      List<String> style) {}
}
