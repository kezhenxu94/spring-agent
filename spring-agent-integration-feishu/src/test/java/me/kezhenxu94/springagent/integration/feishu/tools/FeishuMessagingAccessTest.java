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
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.V1;
import com.lark.oapi.service.im.v1.model.CreateFileResp;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.lark.oapi.service.im.v1.resource.File;
import com.lark.oapi.service.im.v1.resource.Message;
import com.lark.oapi.service.im.v1.resource.MessageResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.FeishuMessageCard;
import me.kezhenxu94.springagent.integration.feishu.drive.FeishuDriveService;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuChatAccess.ChatAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * That the tools which speak into a chat, or take a file out of one, ask first.
 *
 * <p>These three do not name a drive token, so {@link FeishuAccessInterceptor} has nothing to check
 * for them and they are in {@code UNGUARDED} — which only holds because the check happens here
 * instead. Without it a chat id is all it takes to have the bot post into any group it was ever
 * invited to, in somebody else's name, and a message id and a file key are all it takes to pull a
 * file out of a conversation the asker was never in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuMessagingAccessTest {

  private static final String OWN_CHAT = "oc_own";
  private static final String OTHER_CHAT = "oc_others";

  @TempDir Path storage;

  @Mock private Client feishu;
  @Mock private ImService im;
  @Mock private V1 v1;
  @Mock private Message message;
  @Mock private File file;
  @Mock private MessageResource messageResource;
  @Mock private FeishuChatAccess access;
  @Mock private FeishuMessageCard messageCard;

  private FeishuTools tools;
  private ToolContext context;

  @BeforeEach
  void setUp() throws Exception {
    when(feishu.im()).thenReturn(im);
    when(im.v1()).thenReturn(v1);
    when(v1.message()).thenReturn(message);
    when(v1.file()).thenReturn(file);
    when(v1.messageResource()).thenReturn(messageResource);
    when(messageCard.render(any())).thenReturn("{}");
    when(message.create(any())).thenReturn(sent());

    context =
        new ToolContext(
            Map.of(ToolContexts.KEY_USER_ID, "ou_asker", ToolContexts.KEY_CHAT_ID, OWN_CHAT));

    tools =
        new FeishuTools(
            feishu,
            access,
            new UserWorkspaceFactory(
                FileSystemStorageProperties.builder().location(storage.toString()).build()),
            new JsonMapper(),
            null,
            messageCard,
            new FeishuDriveService(feishu, new JsonMapper()),
            null,
            null,
            null);
  }

  private static CreateMessageResp sent() {
    final var resp = new CreateMessageResp();
    resp.setCode(0);
    return resp;
  }

  private void refuse(final String chatId) {
    doThrow(new ChatAccessDeniedException("Refused: you are not in chat " + chatId))
        .when(access)
        .requireMember(any(), eq(chatId));
  }

  /** The message-read the download does to find out whose conversation the file is in. */
  private void theMessageIsIn(final String chatId) throws Exception {
    final var body =
        ("{\"code\":0,\"msg\":\"ok\",\"data\":{\"items\":[{\"message_id\":\"om_1\",\"chat_id\":\""
                + chatId
                + "\"}]}}")
            .getBytes(StandardCharsets.UTF_8);
    final var raw = new RawResponse();
    raw.setStatusCode(200);
    raw.setContentType("application/json");
    raw.setBody(body);
    when(feishu.get(eq("/open-apis/im/v1/messages/om_1"), any(), eq(AccessTokenType.Tenant), any()))
        .thenReturn(raw);
  }

  @Test
  @DisplayName("a message to a chat the person is not in is refused, and never sent")
  void sendMessageToAChatTheyAreNotIn() throws Exception {
    refuse(OTHER_CHAT);

    assertThatThrownBy(() -> tools.sendMessage(OTHER_CHAT, "chat_id", "hello", context))
        .isInstanceOf(ChatAccessDeniedException.class);

    verify(message, never()).create(any());
  }

  @Test
  @DisplayName("a message to a chat they are in goes out")
  void sendMessageToAChatTheyAreIn() throws Exception {
    tools.sendMessage(OWN_CHAT, "chat_id", "hello", context);

    verify(access).requireMember(context, OWN_CHAT);
    verify(message).create(any());
  }

  @Test
  @DisplayName("a message to a person is not a chat to be outside of, so no membership is asked")
  void sendMessageToAPersonIsNotAChatCheck() throws Exception {
    tools.sendMessage("ou_colleague", "open_id", "hello", context);

    verify(access, never()).requireMember(any(), any());
    verify(message).create(any());
  }

  @Test
  @DisplayName("a file sent to a chat they are not in is refused before it is even uploaded")
  void sendFileToAChatTheyAreNotIn() throws Exception {
    refuse(OTHER_CHAT);
    final var local = workspaceFile("notes.txt");

    assertThatThrownBy(() -> tools.sendFile(local.toString(), OTHER_CHAT, "chat_id", context))
        .isInstanceOf(ChatAccessDeniedException.class);

    verifyNoInteractions(file);
    verify(message, never()).create(any());
    assertThat(local).exists();
  }

  @Test
  @DisplayName("a file with no recipient goes to this run's own chat, which is still checked")
  void sendFileWithNoRecipientChecksTheCurrentChat() throws Exception {
    when(file.create(any())).thenReturn(uploaded());
    final var local = workspaceFile("notes.txt");

    tools.sendFile(local.toString(), null, null, context);

    verify(access).requireMember(context, OWN_CHAT);
  }

  @Test
  @DisplayName("a file is not taken out of a conversation the person is not in")
  void downloadFromAChatTheyAreNotIn() throws Exception {
    theMessageIsIn(OTHER_CHAT);
    refuse(OTHER_CHAT);

    assertThatThrownBy(() -> tools.downloadFeishuFile("om_1", "file_key", "notes.txt", context))
        .isInstanceOf(ChatAccessDeniedException.class);

    verifyNoInteractions(messageResource);
  }

  @Test
  @DisplayName("and is when they are, having asked about the chat the message is actually in")
  void downloadFromAChatTheyAreIn() throws Exception {
    theMessageIsIn(OWN_CHAT);
    final var resource = new GetMessageResourceResp();
    resource.setCode(0);
    resource.setData(new java.io.ByteArrayOutputStream());
    when(messageResource.get(any())).thenReturn(resource);

    tools.downloadFeishuFile("om_1", "file_key", "notes.txt", context);

    verify(access).requireMember(context, OWN_CHAT);
  }

  private static CreateFileResp uploaded() {
    final var resp = new CreateFileResp();
    resp.setCode(0);
    final var body = new com.lark.oapi.service.im.v1.model.CreateFileRespBody();
    body.setFileKey("file_key");
    resp.setData(body);
    return resp;
  }

  private Path workspaceFile(final String name) throws Exception {
    final var home = storage.resolve("ou_asker").resolve("workspace");
    Files.createDirectories(home);
    return Files.writeString(home.resolve(name), "hello");
  }
}
