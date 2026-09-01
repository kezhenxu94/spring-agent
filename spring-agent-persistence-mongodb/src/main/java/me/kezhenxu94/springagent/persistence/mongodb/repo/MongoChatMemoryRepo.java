package me.kezhenxu94.springagent.persistence.mongodb.repo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * A conversation, in the order it was said.
 *
 * <p><b>Why this exists rather than Spring AI's {@code MongoChatMemoryRepository}.</b> That one
 * stamps each message with {@code Instant.now()} as it maps the list to documents, and reads them
 * back sorted by that timestamp. A turn is saved in one call — the user's message and the answer to
 * it together — so the whole list is mapped inside a few microseconds, and BSON stores a date to
 * millisecond precision: every message of a turn lands on the same millisecond. The sort then has
 * nothing to order them by, MongoDB breaks the tie however it likes, and a two-message conversation
 * comes back answer-first.
 *
 * <p>That is not merely a display bug. The same memory is what the model is given as the history of
 * the conversation, so a scrambled read teaches it that it answered before it was asked — and on
 * the next turn it is reasoning about a transcript that never happened.
 *
 * <p>The fix is the one Spring AI's JDBC repository already uses: store the position explicitly and
 * order by it. {@code sequenceId} is that position, and the collection is unchanged — {@code
 * ai_chat_memory}, same documents, one field more — so a deployment that has been running the
 * upstream repository keeps its history. Those older documents have no {@code sequenceId}, which is
 * why {@code timestamp} remains the secondary sort: they read exactly as well (or as badly) as they
 * did before, and the next turn in a conversation rewrites the whole of it with positions, since
 * that is what {@link #saveAll} does.
 *
 * <p><b>This is meant to be deleted.</b> spring-projects/spring-ai#6895 makes the upstream
 * repository do the same thing — one timestamp for the batch, a top-level {@code sequenceId} per
 * position — and stores tool calls besides. Its documents and these are the same documents, and the
 * two sorts differ only in which key they name first, so a conversation written by either reads
 * correctly through the other and no migration is needed in either direction. When a release
 * carrying that change is picked up, deleting this class means also: dropping {@code
 * chatMemoryRepository} and {@code @EnableConfigurationProperties(MongoChatMemoryProperties.class)}
 * from {@code MongoPersistenceAutoConfiguration}, dropping {@code SUPERSEDED_BY_MONGODB} from
 * core's {@code PersistenceAutoConfigurationFilter}, pointing {@code ChatMemoryMongoTest} back at
 * the upstream type, dropping the {@code Entry} hints from {@code MongoPersistenceRuntimeHints},
 * and narrowing {@code AskedQuestionsRecorder} to JPA alone — its whole reason on this backend is
 * that a tool call left no trace, which that change fixes. {@code
 * AbstractPersistenceBackendTest#chatMemoryPreservesTheOrderOfATurn} is what confirms the swap, and
 * what would notice if it were wrong.
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
    public record Body(String content, String type, Map<String, Object> metadata) {}
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
    // Dropped rather than stored, matching what the upstream repository and the JDBC one do: a tool
    // response and the assistant message carrying its call are only meaningful as a pair inside the
    // turn that made them, and a history that has one without the other is refused by the model.
    // core's AskedQuestionsRecorder is what leaves a readable trace of an ask on this backend.
    final var persistable =
        messages.stream()
            .filter(
                it ->
                    !(it instanceof ToolResponseMessage)
                        && !(it instanceof AssistantMessage assistant && assistant.hasToolCalls()))
            .toList();
    if (persistable.size() < messages.size()) {
      log.debug(
          "Dropping {} tool message(s) of conversation {}, which MongoDB chat memory does not keep",
          messages.size() - persistable.size(),
          conversationId);
    }

    // Rewritten whole, which is the contract: ChatMemory hands over the conversation as it should
    // now be, trimmed to its window, rather than the delta.
    deleteByConversationId(conversationId);
    if (persistable.isEmpty()) {
      return;
    }
    // One timestamp for the whole conversation rather than one per message, which is the honest
    // thing: they were all written at this moment, and a BSON date could not tell them apart
    // anyway. sequenceId is what orders them.
    final var now = Instant.now();
    final var entries = new java.util.ArrayList<Entry>(persistable.size());
    for (var position = 0; position < persistable.size(); position++) {
      final var message = persistable.get(position);
      entries.add(
          new Entry(
              conversationId,
              new Entry.Body(
                  message.getText(), message.getMessageType().name(), message.getMetadata()),
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

  /** Null for a type this backend does not keep, which the caller filters out. */
  private static Message message(final Entry entry) {
    final var content = entry.message().content() == null ? "" : entry.message().content();
    final var metadata =
        entry.message().metadata() == null ? Map.<String, Object>of() : entry.message().metadata();
    return switch (entry.message().type()) {
      case "USER" -> UserMessage.builder().text(content).metadata(metadata).build();
      case "ASSISTANT" -> AssistantMessage.builder().content(content).properties(metadata).build();
      case "SYSTEM" -> SystemMessage.builder().text(content).metadata(metadata).build();
      case "TOOL" -> null;
      default -> {
        // Skipped rather than thrown, where upstream throws: a single unreadable row would
        // otherwise make a whole conversation unopenable, and the conversation is the thing worth
        // saving here.
        log.warn(
            "Ignoring a message of conversation {} with unsupported type {}",
            entry.conversationId(),
            entry.message().type());
        yield null;
      }
    };
  }
}
