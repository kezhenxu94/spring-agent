package me.kezhenxu94.springagent.integration.feishu.handler;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.core.dao.models.PendingQuestion;
import me.kezhenxu94.springagent.core.dao.repo.PendingQuestionRepo;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import me.kezhenxu94.springagent.integration.feishu.model.AudioMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.FileMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.ImageMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.MediaMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.MessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.PostMessageContent;
import me.kezhenxu94.springagent.integration.feishu.model.TextMessageContent;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuMessageReceiveHandler extends ImService.P2MessageReceiveV1Handler {
  final JsonMapper om;
  final FeishuProperties feishuProperties;
  final Client feishu;

  final SpringAgent springAgent;
  final UserWorkspaceFactory userWorkspaceFactory;
  final FeishuTools feishuTools;
  final PendingQuestionRepo pendingQuestionRepo;
  final FeishuQuestionFormCloser questionFormCloser;

  /**
   * Marks anything the agent was still waiting to hear back on in this conversation as overtaken by
   * what just arrived, and takes its form off the card that carries it — a form the row behind it
   * no longer backs is a control that can only be pressed to be refused.
   *
   * <p>Best effort: a failure here costs a stale form the chance to be refused, not this message
   * the chance to be answered. The card is closed per question rather than for the batch, so one
   * card that cannot be written to does not leave the others standing.
   */
  private void supersedePendingQuestions(final String conversationId) {
    try {
      pendingQuestionRepo
          .findByConversationIdAndStatus(conversationId, PendingQuestion.Status.PENDING)
          .forEach(
              pending -> {
                pendingQuestionRepo.updateStatus(pending.id(), PendingQuestion.Status.SUPERSEDED);
                try {
                  questionFormCloser.superseded(pending);
                } catch (Exception e) {
                  log.warn("Failed to close superseded question form {}", pending.id(), e);
                }
              });
    } catch (Exception e) {
      log.warn("Failed to supersede pending questions in conversation {}", conversationId, e);
    }
  }

  @Override
  public void handle(final P2MessageReceiveV1 event) throws Exception {
    final var data = event.getEvent();
    final var message = data.getMessage();
    final var messageId = message.getMessageId();
    final var parentId = message.getParentId();
    final var rootId =
        !Strings.isNullOrEmpty(message.getRootId()) ? message.getRootId() : messageId;
    final var userOpenId = data.getSender().getSenderId().getOpenId();

    log.info(
        "Received message: rootId={}, messageId={}, chatId={}, chatType={}, parentId={},"
            + " userOpenId={}, content={}",
        rootId,
        messageId,
        message.getChatId(),
        message.getChatType(),
        parentId,
        userOpenId,
        message.getContent());

    if (!springAgent.isAccepting()) {
      throw new IllegalStateException("Shutting down, ignoring message: " + messageId);
    }

    if ("group".equalsIgnoreCase(message.getChatType()) && !isBotMentioned(message)) {
      log.info(
          "Ignoring group message {} in chat {}: bot not mentioned",
          messageId,
          message.getChatId());
      return;
    }

    // Whatever this message says, it is the user's answer to anything the agent was still waiting
    // on in this conversation — they replied instead of using the form. Closing those off now stops
    // the form, pressed later, from starting a second run with an answer that has been overtaken.
    supersedePendingQuestions(rootId);

    if (message.getMentions() != null && message.getMentions().length > 0) {
      message.setContent(
          Stream.of(message.getMentions())
              .reduce(
                  message.getContent(),
                  (content, mention) -> content.replaceAll(mention.getKey(), mention.getName()),
                  (a, b) -> a));
    }

    final var mentionsText =
        message.getMentions() == null || message.getMentions().length == 0
            ? "none"
            : Stream.of(message.getMentions())
                .map(m -> m.getName() + " (" + m.getId().getOpenId() + ")")
                .collect(Collectors.joining(", "));
    springAgent.fire(
        AgentRequest.builder()
            .requestId(messageId)
            .scenario(AgentScenario.CHAT)
            .userId(userOpenId)
            .chatId(message.getChatId())
            .chatType(message.getChatType())
            .conversationId(rootId)
            .rootMessageId(rootId)
            .replyMessageId(message.getMessageId())
            .promptVariables(
                Map.of(
                    "mentions",
                    mentionsText,
                    "threadId",
                    Strings.nullToEmpty(message.getThreadId()),
                    "parentId",
                    Strings.nullToEmpty(parentId)))
            .userMessage(
                user ->
                    addToChat(
                        user::text,
                        message.getMessageId(),
                        message.getMessageType(),
                        message.getContent(),
                        userOpenId,
                        feishuTools))
            .build());
  }

  boolean isBotMentioned(EventMessage message) {
    return message.getMentions() != null
        && Stream.of(message.getMentions())
            .anyMatch(mention -> feishuProperties.botOpenId().equals(mention.getId().getOpenId()));
  }

  @SneakyThrows
  String downloadFeishuImageToLocal(String messageId, String imageKey, String userOpenId) {
    log.info("Downloading image: {}", imageKey);
    final var response =
        feishu
            .im()
            .v1()
            .messageResource()
            .get(
                GetMessageResourceReq.newBuilder()
                    .messageId(messageId)
                    .fileKey(imageKey)
                    .type("image")
                    .build());
    if (!response.success()) {
      log.warn("Failed to get image: {}, {}, {}", imageKey, response.getCode(), response.getMsg());
      return null;
    }
    final var bytes = response.getData().toByteArray();
    log.info("Downloaded image: {}, size={} bytes", imageKey, bytes.length);
    final var artifacts = userWorkspaceFactory.forOwner(userOpenId).artifacts();
    final var dest = artifacts.resolve(imageKey + ".png");
    Files.write(dest, bytes);
    log.info("Saved image to local artifacts: imageKey={}, path={}", imageKey, dest);
    return dest.toString();
  }

  void addToChat(
      final Consumer<String> text,
      final String messageID,
      final String messageType,
      final String content,
      final String userOpenId,
      final FeishuTools feishuTools) {
    log.info("Adding content to chat: {}, {}, {}", messageID, messageType, content);
    try {
      // Feishu stickers are sent as a bare file_key, indistinguishable from a plain file message
      // by content shape alone, so the outer msg_type is the only reliable discriminator.
      if ("sticker".equals(messageType)) {
        text.accept(
            "User sent a sticker, but Feishu clearly documented that sticker file can't be"
                + " downloaded, please ask the user to click the sticker and take a screenshot of"
                + " that sticker, send it as an image instead.");
        return;
      }
      switch (om.readValue(content, MessageContent.class)) {
        case AudioMessageContent audio -> {
          log.info("Received audio message: {}, fileKey={}", messageID, audio.fileKey());
          text.accept(
              "User sent an audio message, message id is: "
                  + messageID
                  + " file key is: "
                  + audio.fileKey()
                  + ", please download it from the Feishu chat and then use TranscribeAudio tool to"
                  + " get the text.");
        }
        case FileMessageContent file -> {
          final var dest =
              feishuTools.downloadFeishuFile(
                  messageID,
                  file.fileKey(),
                  file.fileName(),
                  new ToolContext(Map.of(ToolContexts.KEY_USER_ID, userOpenId)));
          if (dest != null) {
            text.accept("User shared a file: " + file.fileName() + ", saved at: " + dest);
          }
        }
        case ImageMessageContent image -> {
          final var localPath = downloadFeishuImageToLocal(messageID, image.imageKey(), userOpenId);
          text.accept(
              localPath != null
                  ? "The image was saved to: "
                      + localPath
                      + ". Use the RecognizeImage tool to see what it shows."
                  : image.imageKey());
        }
        case MediaMessageContent mediaContent -> {}
        case PostMessageContent post -> {
          log.info("Processing post content: {}, {}", post.title(), post.content());
          final var title = post.title();
          final var elements = post.content().stream().flatMap(c -> c.stream()).toList();
          final var txt =
              elements.stream()
                  .map(it -> it.text())
                  .filter(Predicate.not(Strings::isNullOrEmpty))
                  .collect(Collectors.joining(" ", title + "\n", ""));
          log.info("Post content text: {}", txt);

          final var imageElements = elements.stream().filter(it -> "img".equals(it.tag())).toList();
          log.info("Post content has {} image(s), messageId={}", imageElements.size(), messageID);

          final var fullText = new StringBuilder(txt);
          if (!imageElements.isEmpty()) {
            final var imagesXml = new StringBuilder("<images>\n");
            imageElements.forEach(
                imgTag -> {
                  log.info(
                      "Processing post image: imageKey={}, messageId={}",
                      imgTag.imageKey(),
                      messageID);
                  final var localPath =
                      downloadFeishuImageToLocal(messageID, imgTag.imageKey(), userOpenId);
                  imagesXml.append("<image>\n");
                  imagesXml.append("<key>").append(imgTag.imageKey()).append("</key>\n");
                  if (localPath != null) {
                    imagesXml.append("<path>").append(localPath).append("</path>\n");
                  } else {
                    log.warn(
                        "Failed to save post image locally: imageKey={}, messageId={}",
                        imgTag.imageKey(),
                        messageID);
                  }
                  imagesXml.append("</image>\n");
                });
            imagesXml.append("</images>");
            log.info("Built images XML for messageId={}: {}", messageID, imagesXml);
            fullText
                .append("\n")
                .append(imagesXml)
                .append("\nUse the RecognizeImage tool to see what these images show.");
          }
          text.accept(fullText.toString());
        }
        case TextMessageContent textContent -> text.accept(textContent.text());
        default -> text.accept(content);
      }
    } catch (Exception e) {
      log.warn("Failed to parse message content: {}, using raw content", content, e);
      text.accept(content);
    }
  }
}
