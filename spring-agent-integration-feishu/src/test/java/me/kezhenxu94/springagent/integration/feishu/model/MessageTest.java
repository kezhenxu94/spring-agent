package me.kezhenxu94.springagent.integration.feishu.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageTest {

  private final JsonMapper om = new JsonMapper();

  @Test
  void serializesContentAndUuid() {
    // content/uuid previously had no explicit @JsonProperty, and this mapper's JsonMapper does
    // not auto-detect unannotated Lombok-generated getters the way a plain Jackson 2 ObjectMapper
    // does — every unannotated field silently vanished from the serialized JSON, producing a
    // request Feishu rejected with "content is required" for every reply that used it.
    final var message =
        Message.builder()
            .messageType(MessageType.INTERACTIVE)
            .content("{\"type\":\"template\"}")
            .uuid("some-uuid")
            .build();

    final var json = om.writeValueAsString(message);

    assertThat(json).contains("\"content\":\"{\\\"type\\\":\\\"template\\\"}\"");
    assertThat(json).contains("\"uuid\":\"some-uuid\"");
  }
}
