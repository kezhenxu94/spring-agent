package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.error.Error;
import com.lark.oapi.core.response.error.ErrorFieldViolation;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.V1;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import com.lark.oapi.service.im.v1.model.CreateImageRespBody;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.resource.Image;
import com.lark.oapi.service.im.v1.resource.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.FeishuImages;
import me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuChatAccess.ChatAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * That a message the model composes reaches Feishu as written, that anything on a card which would
 * call back into this application is refused before it is sent, that images are uploaded from the
 * workspace and nowhere else, and that when Feishu refuses, its reasons reach the model.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuSendRichMessageTest {

  private static final String OWN_CHAT = "oc_own";

  @TempDir Path storage;

  @Mock private Client feishu;
  @Mock private ImService im;
  @Mock private V1 v1;
  @Mock private Message message;
  @Mock private Image image;
  @Mock private FeishuChatAccess access;
  @Mock private FeishuMessageCard messageCard;

  private FeishuTools tools;
  private ToolContext context;

  @BeforeEach
  void setUp() throws Exception {
    when(feishu.im()).thenReturn(im);
    when(im.v1()).thenReturn(v1);
    when(v1.message()).thenReturn(message);
    when(v1.image()).thenReturn(image);
    when(message.create(any())).thenReturn(sent(0));
    when(messageCard.render(any())).thenAnswer(call -> call.getArgument(0));

    context =
        new ToolContext(
            Map.of(ToolContexts.KEY_USER_ID, "ou_asker", ToolContexts.KEY_CHAT_ID, OWN_CHAT));
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    tools =
        new FeishuTools(
            feishu,
            access,
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(storage.toString()).build()),
            new JsonMapper(),
            messages,
            messageCard,
            null,
            null,
            null,
            null,
            new FeishuImages(feishu, messages),
            null);
  }

  private static CreateMessageResp sent(final int code) {
    final var resp = new CreateMessageResp();
    resp.setCode(code);
    return resp;
  }

  private void imagesUploadAs(final String key) throws Exception {
    final var resp = new CreateImageResp();
    resp.setCode(0);
    final var body = new CreateImageRespBody();
    body.setImageKey(key);
    resp.setData(body);
    when(image.create(any())).thenReturn(resp);
  }

  private Path workspaceImage(final String name) throws Exception {
    final var home = storage.resolve("ou_asker").resolve("workspace");
    Files.createDirectories(home);
    return Files.write(home.resolve(name), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
  }

  private CreateMessageReq sentRequest() throws Exception {
    final var captor = ArgumentCaptor.forClass(CreateMessageReq.class);
    verify(message).create(captor.capture());
    return captor.getValue();
  }

  private String send(final String msgType, final String json) {
    return tools.sendRichMessage(OWN_CHAT, "chat_id", msgType, json, context);
  }

  @Test
  @DisplayName("a card goes out as an interactive message carrying the JSON it was given")
  void sendsACard() throws Exception {
    final var card =
        "{\"schema\":\"2.0\",\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"Hi\"}},"
            + "\"body\":{\"elements\":[{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\","
            + "\"content\":\"Open\"},\"behaviors\":[{\"type\":\"open_url\","
            + "\"default_url\":\"https://example.com\"}]}]}}";

    assertThat(send("interactive", card)).contains("sent successfully");

    final var request = sentRequest();
    assertThat(request.getCreateMessageReqBody().getMsgType()).isEqualTo("interactive");
    assertThat(new JsonMapper().readTree(request.getCreateMessageReqBody().getContent()))
        .isEqualTo(new JsonMapper().readTree(card));
  }

  @Test
  @DisplayName("JSON that does not parse is answered with where it broke, and nothing is sent")
  void invalidJson() throws Exception {
    assertThat(send("interactive", "{\"schema\": \"2.0\",\n  \"body\": }"))
        .contains("not valid JSON")
        .contains("line 2");

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a message type outside the list is refused, naming the ones that are allowed")
  void unsupportedType() throws Exception {
    assertThat(send("file", "{\"file_key\":\"x\"}")).contains("interactive, post").contains("file");

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a callback behavior is refused with where it is")
  void callbackBehaviorRefused() throws Exception {
    final var card =
        "{\"schema\":\"2.0\",\"body\":{\"elements\":[{\"tag\":\"button\","
            + "\"behaviors\":[{\"type\":\"callback\",\"value\":{\"button\":\"suggestion\"}}]}]}}";

    assertThat(send("interactive", card)).contains("$.body.elements[0].behaviors[0]");

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a form, a button value and an overflow option value are each refused")
  void otherCallbacksRefused() throws Exception {
    assertThat(
            send(
                "interactive",
                "{\"schema\":\"2.0\",\"body\":{\"elements\":[{\"tag\":\"form\",\"elements\":[]}]}}"))
        .contains("$.body.elements[0] (form)");
    assertThat(
            send(
                "interactive",
                "{\"elements\":[{\"tag\":\"button\",\"value\":{\"button\":\"stop\"}}]}"))
        .contains("$.elements[0].value");
    assertThat(
            send(
                "interactive",
                "{\"schema\":\"2.0\",\"body\":{\"elements\":[{\"tag\":\"overflow\","
                    + "\"options\":[{\"text\":{\"tag\":\"plain_text\",\"content\":\"a\"},"
                    + "\"value\":\"a\"}]}]}}"))
        .contains("$.body.elements[0].options[0].value");

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a field called value in a chart's data is not mistaken for a callback")
  void chartValuesAreNotCallbacks() throws Exception {
    final var card =
        "{\"schema\":\"2.0\",\"body\":{\"elements\":[{\"tag\":\"chart\",\"chart_spec\":"
            + "{\"type\":\"bar\",\"data\":{\"values\":[{\"x\":\"a\",\"value\":1}]}}}]}}";

    assertThat(send("interactive", card)).contains("sent successfully");
  }

  @Test
  @DisplayName("a template or a card entity is refused, since what it calls back cannot be seen")
  void templateRefused() throws Exception {
    assertThat(send("interactive", "{\"type\":\"template\",\"data\":{\"template_id\":\"ctp_x\"}}"))
        .contains("template");

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a workspace path where an image key goes is uploaded and replaced by its key")
  void uploadsWorkspaceImages() throws Exception {
    imagesUploadAs("img_v3_key");
    final var picture = workspaceImage("chart.png");
    final var card =
        "{\"schema\":\"2.0\",\"body\":{\"elements\":["
            + "{\"tag\":\"img\",\"img_key\":\""
            + picture
            + "\",\"alt\":{\"tag\":\"plain_text\",\"content\":\"\"}},"
            + "{\"tag\":\"markdown\",\"content\":\"see ![chart]("
            + picture
            + ")\"}]}}";

    assertThat(send("interactive", card)).contains("sent successfully");

    final var content = sentRequest().getCreateMessageReqBody().getContent();
    assertThat(content).contains("\"img_key\":\"img_v3_key\"").contains("![chart](img_v3_key)");
    assertThat(content).doesNotContain(picture.toString());
  }

  @Test
  @DisplayName("an image outside the workspace, or at a URL, is refused and nothing is uploaded")
  void imagesOutsideTheWorkspaceRefused() throws Exception {
    assertThat(send("image", "{\"image_key\":\"/etc/hosts\"}")).contains("/etc/hosts");
    assertThat(send("image", "{\"image_key\":\"https://example.com/a.png\"}")).contains("is a URL");

    verifyNoInteractions(image);
    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("an image key already uploaded is passed through untouched")
  void imageKeysPassThrough() throws Exception {
    send("image", "{\"image_key\":\"img_v3_existing\"}");

    verifyNoInteractions(image);
    assertThat(sentRequest().getCreateMessageReqBody().getContent())
        .isEqualTo("{\"image_key\":\"img_v3_existing\"}");
  }

  @Test
  @DisplayName("a chat the person is not in is refused before anything else")
  void notAMember() throws Exception {
    doThrow(new ChatAccessDeniedException("Refused"))
        .when(access)
        .requireMember(any(), eq("oc_others"));

    assertThatThrownBy(
            () ->
                tools.sendRichMessage("oc_others", "chat_id", "text", "{\"text\":\"hi\"}", context))
        .isInstanceOf(ChatAccessDeniedException.class);

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("when Feishu refuses the card, the field it objected to reaches the model")
  void feishuRefusalIsPassedOn() throws Exception {
    final var refused = sent(230099);
    refused.setMsg("Failed to create card content");
    final var violation = new ErrorFieldViolation();
    violation.setField("body.elements[0].label");
    violation.setDescription("unknown property");
    final var error = new Error();
    error.setFieldViolations(new ErrorFieldViolation[] {violation});
    error.setLogId("log_1");
    refused.setError(error);
    when(message.create(any())).thenReturn(refused);

    assertThat(send("interactive", "{\"schema\":\"2.0\",\"body\":{\"elements\":[]}}"))
        .contains("code 230099")
        .contains("body.elements[0].label: unknown property")
        .contains("log_id: log_1");
  }

  @Test
  @DisplayName("the markdown message uploads a workspace image instead of sending a broken path")
  void markdownMessageUploadsImages() throws Exception {
    imagesUploadAs("img_v3_md");
    final var picture = workspaceImage("plot.png");

    tools.sendMessage(OWN_CHAT, "chat_id", "![plot](" + picture + ")", context);

    verify(messageCard).render("![plot](img_v3_md)");
  }
}
