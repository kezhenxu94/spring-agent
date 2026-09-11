package me.kezhenxu94.springagent.persistence.mongodb.repo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A conversation, in the order it was said, tool calls and all.
 *
 * <p><b>Why this exists rather than Spring AI's {@code MongoChatMemoryRepository}.</b> That one
 * gets two things wrong, and neither can be fixed from outside it.
 *
 * <p>It stamps each message with {@code Instant.now()} as it maps the list to documents, and reads
 * them back sorted by that timestamp. A turn is saved in one call — the user's message and the
 * answer to it together — so the whole list is mapped inside a few microseconds, and BSON stores a
 * date to millisecond precision: every message of a turn lands on the same millisecond. The sort
 * then has nothing to order them by, MongoDB breaks the tie however it likes, and a two-message
 * conversation comes back answer-first.
 *
 * <p>That is not merely a display bug. The same memory is what the model is given as the history of
 * the conversation, so a scrambled read teaches it that it answered before it was asked — and on
 * the next turn it is reasoning about a transcript that never happened.
 *
 * <p>And it drops every {@link ToolResponseMessage} and every {@link AssistantMessage} carrying
 * tool calls, because its document had nowhere to put a call's id, name or arguments. A run that
 * used a tool therefore left a conversation with the reasoning removed: the model is told it
 * answered, never that it looked anything up, so the next turn cannot build on what a tool
 * returned. Ordering matters twice over here, because a provider rejects a tool response that is
 * not immediately preceded by the assistant message requesting it.
 *
 * <p>The fix to both is the one Spring AI's Redis repository already uses for tool calls and its
 * JDBC one for ordering: store the position explicitly and order by it, and keep {@code toolCalls}
 * and {@code toolResponses} on the document. The collection is unchanged — {@code ai_chat_memory},
 * same documents, three fields more — so a deployment that has been running the upstream repository
 * keeps its history.
 *
 * <p><b>TODO: this is meant to be deleted.</b> <a
 * href="https://github.com/spring-projects/spring-ai/pull/6895">spring-projects/spring-ai#6895</a>
 * makes the upstream repository do all of the above, and this class is deliberately written to that
 * pull request's storage shape: the same field names, the same BSON types, the same treatment of a
 * document written before either field existed. A conversation written by either reads correctly
 * through the other and no migration is needed in either direction. When a release carrying that
 * change is picked up, deleting this class means also: dropping {@code chatMemoryRepository} and
 * {@code @EnableConfigurationProperties(MongoChatMemoryProperties.class)} from {@code
 * MongoPersistenceAutoConfiguration}, dropping {@code SUPERSEDED_BY_MONGODB} from core's {@code
 * PersistenceAutoConfigurationFilter}, pointing {@code ChatMemoryMongoTest} back at the upstream
 * type, and dropping the hints from {@code MongoPersistenceRuntimeHints}. {@code
 * AbstractPersistenceBackendTest#chatMemoryPreservesTheOrderOfATurn} and {@code
 * ChatMemoryMongoTest} are what confirm the swap, and what would notice if it were wrong.
 *
 * <p>Two differences from that pull request are deliberate and outlive it, so a straight swap is
 * not quite a straight swap:
 *
 * <ul>
 *   <li>Message metadata is not stored — see {@link Entry.Body} for why keeping it broke whole
 *       conversations on one provider. Upstream keeps it, and a document holding a field nothing
 *       asks for is simply not read, so the swap is still safe in that direction.
 *   <li>The sort names {@code sequenceId} first and {@code timestamp} second, where upstream names
 *       them the other way round. Both are correct — a conversation is rewritten whole, so every
 *       document of one shares a timestamp — and they differ only for documents written before
 *       {@code sequenceId} existed, which this order reads exactly as well (or as badly) as the
 *       upstream repository did before it.
 * </ul>
 *
 * <p>One caveat that outlives the swap: the compound index is created only when missing, so a
 * deployment that has already created the two-field one keeps it and the new sort is served by a
 * partial index. Drop it by hand to have it rebuilt.
 */
@Slf4j
@RequiredArgsConstructor
public class MongoChatMemoryRepo implements ChatMemoryRepository {

  /** Spring AI's collection, deliberately: this replaces that repository rather than shadowing. */
  static final String COLLECTION = "ai_chat_memory";

  /**
   * One message of one conversation.
   *
   * @param sequenceId where it comes in the conversation. Absent on a document written by the
   *     upstream repository before this class existed. Boxed rather than {@code int} so that such a
   *     document still maps — Spring Data passes null for a field that is not there, and a record
   *     component cannot default. An {@code Integer} rather than a {@code Long} so the stored BSON
   *     is an int32, which is what spring-projects/spring-ai#6895 reads it back as
   */
  @Document(COLLECTION)
  public record Entry(String conversationId, Body message, Instant timestamp, Integer sequenceId) {

    /**
     * What is kept of one message: its text, its role, and whatever tool calling it carried —
     * deliberately not its metadata.
     *
     * <p>This used to carry {@code Map<String, Object> metadata} straight off the message, which
     * was a divergence from the other two backends nobody had written down — Spring AI's JDBC
     * repository, which serves {@code jpa} and {@code redis} here, selects {@code content, type,
     * timestamp} and has never stored any. So nothing in this project can depend on it, because a
     * deployment on either of those has never had it.
     *
     * <p>Keeping it was actively harmful. A message's metadata is provider telemetry, and a
     * provider is free to put its own SDK objects in there: on {@code google-genai} it holds a
     * {@code com.google.genai.types.FinishReason}, which Mongo writes happily as a sub-document and
     * then cannot read back — {@code Failed to instantiate ... using constructor NO_CONSTRUCTOR},
     * surfacing as every run in that conversation dying on {@code Stream processing failed}. None
     * of it is needed to replay a conversation, which is the whole job here.
     *
     * <p>Dropping the component rather than ignoring the field is what repairs a database that
     * already holds such documents: Spring Data maps a record by its constructor parameters, so a
     * field nothing asks for is no longer read, and a conversation written before this becomes
     * readable again.
     *
     * <p>Tool calling is the opposite case, and the reason these two are stored rather than
     * summarised into text: they are Spring AI's own records, whose components are strings, so
     * there is nothing a provider can smuggle into them.
     *
     * @param content the text, which is genuinely null on an assistant message that only asks for a
     *     tool and is kept null rather than coerced, so that such a message round-trips as itself
     * @param toolCalls what an assistant message asked for, empty on every other kind. Null on a
     *     document written before this field existed, which for an assistant message is the same
     *     thing as empty
     * @param toolResponses what a tool message answered with, empty on every other kind. Null on a
     *     document written before this field existed, which for a tool message is <em>not</em> the
     *     same thing as empty — see {@link MongoChatMemoryRepo#message}
     */
    public record Body(
        String content,
        String type,
        List<AssistantMessage.ToolCall> toolCalls,
        List<ToolResponseMessage.ToolResponse> toolResponses) {}
  }

  private final MongoTemplate mongoTemplate;

  @Override
  public List<String> findConversationIds() {
    return mongoTemplate.query(Entry.class).distinct("conversationId").as(String.class).all();
  }

  @Override
  public List<Message> findByConversationId(final String conversationId) {
    final var query =
        Query.query(Criteria.where("conversationId").is(conversationId))
            .with(Sort.by(Sort.Order.asc("sequenceId"), Sort.Order.asc("timestamp")));
    return mongoTemplate.query(Entry.class).matching(query).stream()
        .map(MongoChatMemoryRepo::message)
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public void saveAll(final String conversationId, final List<Message> messages) {
    // Rewritten whole, which is the contract: ChatMemory hands over the conversation as it should
    // now be, trimmed to its window, rather than the delta.
    deleteByConversationId(conversationId);
    if (messages.isEmpty()) {
      return;
    }
    // One timestamp for the whole conversation rather than one per message, which is the honest
    // thing: they were all written at this moment, and a BSON date could not tell them apart
    // anyway. sequenceId is what orders them.
    final var now = Instant.now();
    final var entries = new ArrayList<Entry>(messages.size());
    for (var position = 0; position < messages.size(); position++) {
      final var message = messages.get(position);
      final var toolCalls =
          message instanceof AssistantMessage assistant
              ? assistant.getToolCalls()
              : List.<AssistantMessage.ToolCall>of();
      final var toolResponses =
          message instanceof ToolResponseMessage tool
              ? tool.getResponses()
              : List.<ToolResponseMessage.ToolResponse>of();
      entries.add(
          new Entry(
              conversationId,
              new Entry.Body(
                  message.getText(), message.getMessageType().name(), toolCalls, toolResponses),
              now,
              position));
    }
    mongoTemplate.insert(entries, Entry.class);
  }

  @Override
  public void deleteByConversationId(final String conversationId) {
    mongoTemplate.remove(
        Query.query(Criteria.where("conversationId").is(conversationId)), Entry.class);
  }

  /** Null for a message this backend cannot rebuild, which the caller filters out. */
  private static Message message(final Entry entry) {
    final var body = entry.message();
    // A user or system message has to have text; only an assistant one may be null.
    final var content = body.content() == null ? "" : body.content();
    return switch (body.type()) {
      case "USER" -> UserMessage.builder().text(content).build();
      case "ASSISTANT" ->
          AssistantMessage.builder()
              .content(body.content())
              .toolCalls(body.toolCalls() == null ? List.of() : body.toolCalls())
              .build();
      case "SYSTEM" -> SystemMessage.builder().text(content).build();
      case "TOOL" -> {
        if (body.toolResponses() == null) {
          // Written before this backend stored tool responses, when the whole message was dropped
          // on the way in. There is nothing to rebuild it from, and a tool message with no
          // responses is worse than none: the provider is handed an answer to a call it cannot see
          // the question for. Absent is what says so — a tool message genuinely saved with no
          // responses stores an empty array and is kept.
          log.debug(
              "Skipping a tool message of conversation {} written before tool responses were kept",
              entry.conversationId());
          yield null;
        }
        yield ToolResponseMessage.builder().responses(body.toolResponses()).build();
      }
      default -> {
        // Skipped rather than thrown, where upstream throws: a single unreadable row would
        // otherwise make a whole conversation unopenable, and the conversation is the thing worth
        // saving here.
        log.warn(
            "Ignoring a message of conversation {} with unsupported type {}",
            entry.conversationId(),
            body.type());
        yield null;
      }
    };
  }
}
