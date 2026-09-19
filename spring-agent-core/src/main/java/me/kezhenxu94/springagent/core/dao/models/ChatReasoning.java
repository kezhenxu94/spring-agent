package me.kezhenxu94.springagent.core.dao.models;

import com.google.common.hash.Hashing;
import jakarta.persistence.Column;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

/**
 * What one run thought on its way to an answer, kept after the run is over.
 *
 * <p>Reasoning used to live exactly as long as the run did: a card streamed it into a panel and the
 * browser streams it into a fold, and both are gone once the run's journal is evicted or the page
 * is reloaded. A deployment that turns the card's panel off therefore threw it away the moment it
 * was produced. This is where it is kept instead, so a reader can go back to a round and ask why
 * the answer says what it says.
 *
 * <p>The id is the run's {@code requestId} rather than a generated one: the row <em>is</em> the run,
 * so there is nothing to join and no key to invent — the same reasoning {@link ChatSession}'s id
 * gives. That also makes it exact for a surface that still has the id in hand, which is every
 * surface while the run is live.
 *
 * <p>{@link #answerDigest} is for the other case, and the reason it exists is worth knowing before
 * anybody proposes the obvious alternative. A reloaded conversation is replayed out of chat memory,
 * and <em>no</em> backend stores message metadata: Spring AI's JDBC repository, which serves {@code
 * jpa} and {@code redis} here, selects {@code content, type, timestamp, sequence_id} and has
 * nowhere to put anything else, and {@code MongoChatMemoryRepo} dropped metadata deliberately after
 * a provider's own object in it made whole conversations unreadable. So a replayed turn carries no
 * run id and never can without forking all three repositories, two of them upstream. The digest is
 * what pairs a row back to a turn instead: it matches exactly or not at all, and a turn that
 * matches nothing is drawn without any thinking rather than with somebody else's.
 *
 * <p>See {@link ScheduledTask} for why one class carries every backend's mapping annotations, and
 * for what {@code @Indexed} means to Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ChatReasoning.COLLECTION_NAME)
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = ChatReasoning.COLLECTION_NAME)
@RedisHash(ChatReasoning.COLLECTION_NAME)
public class ChatReasoning {
  public static final String COLLECTION_NAME = "bot_chat_reasoning";

  /** The run's {@code requestId}, which is what makes this row the run rather than a row about it. */
  @Id @jakarta.persistence.Id private String id;

  /** findByConversationId, and what a delete of the conversation takes with it. */
  @Indexed private String conversationId;

  /**
   * Whose run it was, so a read can be refused to anybody else without going back to the
   * conversation it belongs to.
   */
  private String userId;

  /**
   * A hex SHA-256 of the final answer, or blank for a run that ended without saying anything. How a
   * replayed turn finds this row — see the class comment.
   */
  @Column(length = 64)
  private String answerDigest;

  /**
   * Everything the run thought, as the last {@code onReasoning} reported it: one block per model
   * call of the turn, which is the shape that callback promises.
   *
   * <p>Not truncated. What is stored is what the model produced, and a reader going back to a round
   * to find out why is badly served by thinking that stops in the middle. The declared length is
   * only what the DDL says — SQLite ignores it entirely, and a length this large is what makes
   * Hibernate choose the long-text type of whichever other database a deployment points {@code jpa}
   * at rather than a varchar it would have to fit in.
   */
  @Column(length = 1048576)
  private String text;

  private Instant createdAt;

  /**
   * How {@link #answerDigest} is computed, here rather than at either end of it: the run writes one
   * and a replayed transcript computes another to look the row up with, and two spellings of the
   * same hash would pair nothing at all while looking perfectly correct on both sides.
   *
   * <p>Blank for an answer that is null or empty, which is a run that ended without saying anything
   * — cancelled, or failed. Such a row is still worth keeping for the surface that still holds the
   * run's id, and blank is what stops it matching a replayed turn by accident.
   */
  public static String digestOf(final String answer) {
    if (answer == null || answer.isEmpty()) {
      return "";
    }
    return Hashing.sha256().hashString(answer, StandardCharsets.UTF_8).toString();
  }
}
