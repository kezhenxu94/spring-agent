package me.kezhenxu94.springagent.handlers;

import com.google.common.base.Strings;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import jakarta.annotation.PostConstruct;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.agent.AgentRequest;
import me.kezhenxu94.springagent.agent.AgentResponseListener;
import me.kezhenxu94.springagent.agent.AgentScenario;
import me.kezhenxu94.springagent.agent.SpringAgent;
import me.kezhenxu94.springagent.bot.configuration.SpringAgentProperties;
import me.kezhenxu94.springagent.dao.models.FeishuMessage;
import me.kezhenxu94.springagent.dao.models.FeishuMessage.Status;
import me.kezhenxu94.springagent.dao.repo.FeishuMessageRepo;
import me.kezhenxu94.springagent.models.AudioMessageContent;
import me.kezhenxu94.springagent.models.FileMessageContent;
import me.kezhenxu94.springagent.models.ImageMessageContent;
import me.kezhenxu94.springagent.models.MediaMessageContent;
import me.kezhenxu94.springagent.models.MessageContent;
import me.kezhenxu94.springagent.models.PostMessageContent;
import me.kezhenxu94.springagent.models.TextMessageContent;
import me.kezhenxu94.springagent.tools.AgentToolsProvider;
import me.kezhenxu94.springagent.tools.FeishuTools;
import me.kezhenxu94.springagent.tools.ToolContexts;
import me.kezhenxu94.springagent.tools.UserWorkspaceFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuMessageReceiveHandler extends ImService.P2MessageReceiveV1Handler {
  final JsonMapper om;
  final SpringAgentProperties appConfiguration;
  final FeishuMessageRepo feishuMessageRepo;
  final Client feishu;

  final SpringAgent springAgent;
  final RestTemplate restTemplate;
  final MessageListenerContainer mongoListenerContainer;
  final MongoTemplate mongoTemplate;
  final AgentToolsProvider agentToolsProvider;
  final UserWorkspaceFactory userWorkspaceFactory;
  final FeishuTools feishuTools;

  @Value("classpath:/feishu/reply-card.json")
  final Resource feishuReplyCard;

  @PostConstruct
  @SuppressWarnings("unchecked")
  public void init() {
    final var listener =
        (MessageListener<ChangeStreamDocument<Document>, ? super FeishuMessage>)
            (event) -> {
              final var message = event.getBody();
              log.info("Message {} is cancelled, removing from processing", message.getId());
              springAgent.cancel(message.getId());
            };

    final var request =
        ChangeStreamRequest.builder()
            .collection(FeishuMessage.COLLECTION_NAME)
            .publishTo(listener)
            .filter(
                Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").is(FeishuMessage.Status.CANCELLED))))
            .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
            .build();
    mongoListenerContainer.register(request, FeishuMessage.class);
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

    if (feishuMessageRepo.existsById(messageId)) {
      log.info("Message {} already exists, skipping", messageId);
      return;
    }

    if ("group".equalsIgnoreCase(message.getChatType()) && !isBotMentioned(message)) {
      log.info(
          "Ignoring group message {} in chat {}: bot not mentioned",
          messageId,
          message.getChatId());
      return;
    }

    final var cardUpdater = createCard();
    if (cardUpdater == null) {
      log.error("Aborting message {}: failed to create reply card", messageId);
      return;
    }
    log.info("Created reply card for message {}: cardId={}", messageId, cardUpdater.getCardId());

    feishuMessageRepo.save(
        FeishuMessage.builder()
            .id(messageId)
            .messageRootId(rootId)
            .status(Status.GENERATING)
            .responseCardId(cardUpdater.getCardId())
            .build());

    final var replyMessageId = sendCardReply(message.getMessageId(), cardUpdater.getCardId());
    if (replyMessageId == null) {
      log.error(
          "Aborting message {}: failed to send card reply for cardId={}",
          messageId,
          cardUpdater.getCardId());
      return;
    }
    log.info(
        "Card reply sent: messageId={}, replyMessageId={}, cardId={}",
        messageId,
        replyMessageId,
        cardUpdater.getCardId());

    feishuMessageRepo.save(
        FeishuMessage.builder()
            .id(replyMessageId)
            .messageRootId(rootId)
            .status(Status.GENERATING)
            .build());

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
    final var promptVariables =
        Map.<String, Object>of(
            "chatId",
            message.getChatId(),
            "chatType",
            message.getChatType(),
            "mentions",
            mentionsText,
            "userId",
            userOpenId,
            "threadId",
            Strings.nullToEmpty(message.getThreadId()),
            "parentId",
            Strings.nullToEmpty(parentId));

    final var composition =
        agentToolsProvider.compose(
            userOpenId,
            message.getChatId(),
            message.getChatType(),
            AgentScenario.CHAT,
            cardUpdater);
    final var agentRequest =
        AgentRequest.builder()
            .promptVariables(promptVariables)
            .userMessage(
                user ->
                    addToChat(
                        user::text,
                        message.getMessageId(),
                        message.getMessageType(),
                        message.getContent(),
                        userOpenId,
                        feishuTools))
            .tools(composition.tools())
            .toolCallbacks(composition.toolCallbacks())
            .toolContext(
                Map.of(
                    ToolContexts.KEY_MESSAGE,
                    message,
                    ToolContexts.KEY_USER_ID,
                    userOpenId,
                    ToolContexts.KEY_CHAT_ID,
                    message.getChatId(),
                    ToolContexts.KEY_CHAT_TYPE,
                    message.getChatType(),
                    ToolContexts.KEY_REPLY_MESSAGE_ID,
                    message.getMessageId(),
                    FeishuCardUpdater.TOOL_CONTEXT_KEY.key(),
                    cardUpdater))
            .conversationId(rootId)
            .memoriesRootDirectory(composition.memoriesRootDirectory())
            .conversationMemory(AgentScenario.CHAT.isConversationMemory())
            .requestId(replyMessageId)
            .build();

    final var lifecycleListener =
        new MessageLifecycleListener(messageId, rootId, composition.agentTools());
    springAgent.stream(agentRequest, cardUpdater, lifecycleListener).subscribe();
  }

  @RequiredArgsConstructor
  private final class MessageLifecycleListener implements AgentResponseListener {
    private final String messageId;
    private final String rootId;
    private final AgentToolsProvider.AgentTools agentTools;

    @Override
    public void onContent(String contentSoFar) {}

    @Override
    public void onUsage(String model, Usage usage) {}

    @Override
    public void onError(Throwable error) {
      if (error instanceof InterruptedIOException) {
        feishuMessageRepo.save(
            FeishuMessage.builder()
                .id(messageId)
                .messageRootId(rootId)
                .status(Status.CANCELLED)
                .build());
        return;
      }
      log.error("Failed to process message {}", messageId, error);
      mongoTemplate.updateFirst(
          new Query(Criteria.where("id").is(messageId)),
          new Update().set("status", FeishuMessage.Status.FAILED),
          FeishuMessage.class);
    }

    @Override
    public void onFinished(SignalType signal) {
      log.info("Completed, {}", signal);
      if (signal == SignalType.ON_COMPLETE) {
        mongoTemplate.updateFirst(
            new Query(Criteria.where("id").is(messageId)),
            new Update().set("status", FeishuMessage.Status.COMPLETED),
            FeishuMessage.class);
      }
      try {
        agentTools.mcpTools().close();
      } catch (Exception e) {
        log.warn("Failed to close MCP clients for message {}", messageId, e);
      }
    }
  }

  @SneakyThrows
  private FeishuCardUpdater createCard() {
    final var resp =
        feishu
            .cardkit()
            .v1()
            .card()
            .create(
                CreateCardReq.newBuilder()
                    .createCardReqBody(
                        CreateCardReqBody.newBuilder()
                            .type("card_json")
                            .data(feishuReplyCard.getContentAsString(StandardCharsets.UTF_8))
                            .build())
                    .build());
    if (resp.getCode() != 0) {
      log.error("Failed to create card: {}", om.writeValueAsString(resp));
      return null;
    }
    log.info("Created card: {}", resp.getData().getCardId());
    return new FeishuCardUpdater(
        feishu, om, resp.getData().getCardId(), restTemplate, appConfiguration.ai().modelPricing());
  }

  @SneakyThrows
  private String sendCardReply(String messageId, String cardId) {
    final var resp =
        feishu
            .im()
            .v1()
            .message()
            .reply(
                ReplyMessageReq.newBuilder()
                    .messageId(messageId)
                    .replyMessageReqBody(
                        ReplyMessageReqBody.newBuilder()
                            .msgType("interactive")
                            .content(
                                String.format(
                                    """
                                    {
                                      "type": "card",
                                      "data": {
                                        "card_id": "%s"
                                      }
                                    }
                                    """,
                                    cardId))
                            .build())
                    .build());
    if (resp.getCode() != 0) {
      log.error("Failed to send card: {}", om.writeValueAsString(resp));
      return null;
    }
    return resp.getData().getMessageId();
  }

  boolean isBotMentioned(EventMessage message) {
    return message.getMentions() != null
        && Stream.of(message.getMentions())
            .anyMatch(
                mention ->
                    appConfiguration.feishu().botOpenId().equals(mention.getId().getOpenId()));
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
                  file.getFileKey(),
                  file.getFileName(),
                  new ToolContext(Map.of(ToolContexts.KEY_USER_ID, userOpenId)));
          if (dest != null) {
            text.accept("User shared a file: " + file.getFileName() + ", saved at: " + dest);
          }
        }
        case ImageMessageContent image -> {
          final var localPath = downloadFeishuImageToLocal(messageID, image.imageKey(), userOpenId);
          text.accept(
              localPath != null
                  ? "图片已保存到: " + localPath + "，如需查看图片内容请使用 ImageTools 的 recognizeImage 工具"
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
            fullText.append("\n").append(imagesXml).append("\n如需查看图片内容请使用 RecognizeImage 工具");
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
