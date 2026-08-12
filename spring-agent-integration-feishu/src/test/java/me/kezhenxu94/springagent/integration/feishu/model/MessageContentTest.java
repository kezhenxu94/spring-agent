package me.kezhenxu94.springagent.integration.feishu.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageContentTest {
  private final JsonMapper objectMapper = new JsonMapper();

  @Test
  void testTextMessageDeserialization() throws Exception {
    String json =
        """
        {
            "text": "@_user_1 文本消息"
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof TextMessageContent);
    assertEquals("text", content.getType());
    assertEquals("@_user_1 文本消息", ((TextMessageContent) content).text());
  }

  @Test
  void testPostMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "title": "我是一个标题",
            "content": [
                [
                    {
                        "tag": "text",
                        "text": "第一行 :",
                        "style": ["bold", "underline"]
                    },
                    {
                        "tag": "a",
                        "href": "http://www.feishu.cn",
                        "text": "超链接",
                        "style": ["bold", "italic"]
                    },
                    {
                        "tag": "at",
                        "user_id": "@_user_1",
                        "user_name": "",
                        "style": []
                    }
                ],
                [
                    {
                        "tag": "img",
                        "image_key": "img_47354fbc-a159-40ed-86ab-2ad0f1acb42g"
                    }
                ]
            ]
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof PostMessageContent);
    assertEquals("post", content.getType());

    final var postContent = (PostMessageContent) content;
    assertEquals("我是一个标题", postContent.title());
    assertEquals(2, postContent.content().size());

    // Verify first row
    final var firstRow = postContent.content().get(0);
    assertEquals(3, firstRow.size());

    // Verify text element
    final var textElement = firstRow.get(0);
    assertEquals("text", textElement.tag());
    assertEquals("第一行 :", textElement.text());
    assertEquals(List.of("bold", "underline"), textElement.style());

    // Verify link element
    final var linkElement = firstRow.get(1);
    assertEquals("a", linkElement.tag());
    assertEquals("超链接", linkElement.text());
    assertEquals("http://www.feishu.cn", linkElement.href());
    assertEquals(List.of("bold", "italic"), linkElement.style());

    // Verify at element
    final var atElement = firstRow.get(2);
    assertEquals("at", atElement.tag());
    assertEquals("@_user_1", atElement.userId());
    assertEquals("", atElement.userName());
    assertTrue(atElement.style().isEmpty());

    // Verify second row (image)
    final var secondRow = postContent.content().get(1);
    assertEquals(1, secondRow.size());
    final var imageElement = secondRow.get(0);
    assertEquals("img", imageElement.tag());
    assertEquals("img_47354fbc-a159-40ed-86ab-2ad0f1acb42g", imageElement.imageKey());
  }

  @Test
  void testImageMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "image_key": "img_4adb3cc3-902b-4187-b0f1-842f67fd017g"
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof ImageMessageContent);
    assertEquals("image", content.getType());
    assertEquals(
        "img_4adb3cc3-902b-4187-b0f1-842f67fd017g", ((ImageMessageContent) content).imageKey());
  }

  @Test
  void testFileMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "file_key": "75235e0c-4f92-430a-a99b-8446610223cg",
            "file_name": "test.txt"
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof FileMessageContent);
    assertEquals("file", content.getType());
    assertEquals("75235e0c-4f92-430a-a99b-8446610223cg", ((FileMessageContent) content).fileKey());
    assertEquals("test.txt", ((FileMessageContent) content).fileName());
  }

  @Test
  void testAudioMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "file_key": "75235e0c-4f92-430a-a99b-8446610223cg",
            "duration": 2000
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof AudioMessageContent);
    assertEquals("audio", content.getType());
    assertEquals("75235e0c-4f92-430a-a99b-8446610223cg", ((AudioMessageContent) content).fileKey());
    assertEquals(2000, ((AudioMessageContent) content).duration());
  }

  @Test
  void testMediaMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "file_key": "75235e0c-4f92-430a-a99b-8446610223cg",
            "image_key": "img_7ea74629-9191-4176-998c-2e603c9c5e8g"
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof MediaMessageContent);
    assertEquals("media", content.getType());
    assertEquals("75235e0c-4f92-430a-a99b-8446610223cg", ((MediaMessageContent) content).fileKey());
    assertEquals(
        "img_7ea74629-9191-4176-998c-2e603c9c5e8g", ((MediaMessageContent) content).imageKey());
  }

  @Test
  void testSimpleFileMessageDeserialization() throws Exception {
    final var json =
        """
        {
            "file_key": "75235e0c-4f92-430a-a99b-8446610223cg"
        }
        """;

    final var content = objectMapper.readValue(json, MessageContent.class);
    assertTrue(content instanceof FileMessageContent);
    assertEquals("file", content.getType());
    assertEquals("75235e0c-4f92-430a-a99b-8446610223cg", ((FileMessageContent) content).fileKey());
    assertNull(((FileMessageContent) content).fileName());
  }

  @Test
  void testUnknownMessageType() {
    final var json =
        """
        {
            "unknown_field": "value"
        }
        """;

    assertThrows(Exception.class, () -> objectMapper.readValue(json, MessageContent.class));
  }
}
