package me.kezhenxu94.springagent.integration.feishu.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
  @JsonIgnore ReceiveIdType receiveType;

  @JsonProperty("receive_id")
  String receiveId;

  @JsonProperty("msg_type")
  MessageType messageType;

  String content;

  String uuid;

  public interface Content {
    static TemplateContent.TemplateContentBuilder newTemplateBuilder() {
      return TemplateContent.builder();
    }

    static TextContent.TextContentBuilder newTextBuilder() {
      return TextContent.builder();
    }
  }

  @Builder
  public record TemplateContent(
      @JsonIgnore String templateId, @JsonIgnore @Singular Map<String, ?> variables)
      implements Content {
    @JsonProperty("type")
    public String getType() {
      return "template";
    }

    @JsonProperty("data")
    public Map<String, ?> getData() {
      return Map.of("template_id", templateId, "template_variable", variables);
    }
  }

  @Builder
  @Jacksonized
  public record TextContent(String text) implements Content {}

  @Builder
  @Jacksonized
  public record PostContent(@JsonProperty("zh_cn") ZhCh zhCh) implements Content {}

  @Builder
  @Jacksonized
  public record ZhCh(@JsonProperty("content") @Singular List<List<TagContent>> contents) {}

  @Builder
  @Jacksonized
  public record TagContent(
      String tag, String text, @JsonProperty("language") String codeLanguage) {}
}
