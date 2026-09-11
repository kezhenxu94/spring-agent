package me.kezhenxu94.springagent.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import me.kezhenxu94.springagent.persistence.mongodb.repo.MongoChatMemoryRepo;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Selecting mongodb swaps the whole conversation store, leaving the JPA one out of the context. */
@SpringBootTest(properties = "app.persistence.type=mongodb")
class ChatMemoryMongoTest extends AbstractIntegrationTest {

  /** Spring AI's collection, which this backend writes rather than one of its own. */
  private static final String COLLECTION = "ai_chat_memory";

  @Autowired ApplicationContext context;
  @Autowired ChatMemoryRepository chatMemoryRepository;
  @Autowired MongoTemplate mongoTemplate;

  @Test
  @DisplayName("exactly one chat memory repository is registered, backed by MongoDB")
  void mongoBacksTheChatMemory() {
    // This module's own rather than Spring AI's, which PersistenceAutoConfigurationFilter keeps out
    // — see MongoChatMemoryRepo for why. `hasSize(1)` is the load-bearing half: upstream's bean
    // backs off in front of nothing, so if the filter ever stopped dropping it the context would
    // hold two repositories and whichever won would be a matter of luck.
    assertThat(context.getBeansOfType(ChatMemoryRepository.class))
        .hasSize(1)
        .allSatisfy(
            (name, repository) -> assertThat(repository).isInstanceOf(MongoChatMemoryRepo.class));
  }

  @Test
  @DisplayName("a turn that called a tool reads back whole, in order")
  void toolCallingRoundTrips() {
    // The exchange a provider is strict about: the call and the answer to it are only valid as a
    // pair, in this order. Dropping either — which is what this backend used to do with both — is
    // what leaves the model told that it answered and never that it looked anything up.
    final var conversationId = "conversation-mongo-tools";
    final var call =
        new AssistantMessage.ToolCall("call-1", "function", "weather", "{\"city\":\"SH\"}");
    final var asking = AssistantMessage.builder().content(null).toolCalls(List.of(call)).build();
    final var answering =
        ToolResponseMessage.builder()
            .responses(
                List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "{\"c\":21}")))
            .build();

    chatMemoryRepository.saveAll(
        conversationId,
        List.of(
            new UserMessage("weather in Shanghai?"),
            asking,
            answering,
            new AssistantMessage("21 degrees.")));

    final var read = chatMemoryRepository.findByConversationId(conversationId);
    assertThat(read)
        .extracting(Message::getMessageType)
        .containsExactly(
            MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL, MessageType.ASSISTANT);

    // Null content is kept null rather than coerced to "", which is the usual shape of a message
    // that only asks for a tool: a provider reading back an empty string sees a different message.
    assertThat((AssistantMessage) read.get(1))
        .satisfies(
            it -> {
              assertThat(it.getText()).isNull();
              assertThat(it.getToolCalls()).containsExactly(call);
            });
    assertThat(((ToolResponseMessage) read.get(2)).getResponses())
        .containsExactly(new ToolResponseMessage.ToolResponse("call-1", "weather", "{\"c\":21}"));

    chatMemoryRepository.deleteByConversationId(conversationId);
  }

  @Test
  @DisplayName("a conversation written before tool calls were kept still reads")
  void legacyDocumentsStillRead() {
    // Raw BSON in the shape Spring AI's repository wrote: no sequenceId, no toolCalls, no
    // toolResponses, one metadata map nothing reads any more, and a distinct timestamp per message
    // because that is what ordered them. A deployment upgrading into this code has a database full
    // of these, and the cost of getting it wrong is a conversation that cannot be opened at all.
    final var conversationId = "conversation-mongo-legacy";
    final var first = Instant.now().minusMillis(50);
    mongoTemplate
        .getCollection(COLLECTION)
        .insertMany(
            List.of(
                legacy(conversationId, "hi", "USER", Date.from(first)),
                legacy(conversationId, "Hello.", "ASSISTANT", Date.from(first.plusMillis(10))),
                // The one that cannot be rebuilt: it was written with its responses already thrown
                // away, so
                // there is nothing to hand a provider but an answer to a question it cannot see.
                legacy(conversationId, "", "TOOL", Date.from(first.plusMillis(20)))));

    assertThat(chatMemoryRepository.findByConversationId(conversationId))
        .extracting(Message::getMessageType, Message::getText)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(MessageType.USER, "hi"),
            org.assertj.core.groups.Tuple.tuple(MessageType.ASSISTANT, "Hello."));

    // And the next turn rewrites the whole conversation with positions and tool fields, so the gap
    // closes on its own rather than needing a migration.
    chatMemoryRepository.saveAll(
        conversationId, List.of(new UserMessage("hi"), new AssistantMessage("Hello.")));
    assertThat(chatMemoryRepository.findByConversationId(conversationId))
        .extracting(Message::getText)
        .containsExactly("hi", "Hello.");

    chatMemoryRepository.deleteByConversationId(conversationId);
  }

  @Test
  @DisplayName("a tool message genuinely saved with no responses is kept")
  void anEmptyToolMessageIsNotLegacy() {
    // The distinction the read path turns on: absent means "written before this backend stored
    // them", empty means "there were none". Reading them the same way would either resurrect
    // unusable stubs or silently drop real messages.
    final var conversationId = "conversation-mongo-empty-tool";
    chatMemoryRepository.saveAll(
        conversationId,
        List.of(new UserMessage("go"), ToolResponseMessage.builder().responses(List.of()).build()));

    assertThat(chatMemoryRepository.findByConversationId(conversationId))
        .extracting(Message::getMessageType)
        .containsExactly(MessageType.USER, MessageType.TOOL);

    chatMemoryRepository.deleteByConversationId(conversationId);
  }

  private static Document legacy(
      final String conversationId, final String content, final String type, final Date timestamp) {
    return new Document("conversationId", conversationId)
        .append(
            "message",
            new Document("content", content)
                .append("type", type)
                .append("metadata", new Document("messageType", type)))
        .append("timestamp", timestamp);
  }
}
