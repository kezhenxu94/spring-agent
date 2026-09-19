package me.kezhenxu94.springagent.core.dao.models;

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
 * Whose conversation a {@code conversationId} is, and nothing else.
 *
 * <p>A surface that shows a person their past conversations has to be able to ask for theirs, and
 * chat memory cannot answer that: Spring AI's repository can enumerate conversation ids, but it
 * knows nothing about who they belong to, so a listing built on it would show every user every
 * other user's threads. This is the missing half — an index from a person to the ids they own. What
 * was said in one of them is still chat memory's business, read back by id.
 *
 * <p>Deliberately holds no preview and no message count, and no <em>derived</em> title. Every one
 * of those is computable from the conversation itself and would be a second copy to keep in step
 * with it — a copy that goes stale exactly when a conversation is edited or trimmed. A surface that
 * wants a name and has not been given one takes the first thing the user said.
 *
 * <p>{@link #title} is the exception that proves the rule rather than a retreat from it: a name
 * somebody typed is not derivable from anything, so it is not a copy of the conversation and cannot
 * go stale against it. It is stored precisely because there is nowhere else it could come from.
 * Blank means nobody has named this one, which is the ordinary case and the one that keeps
 * deriving.
 *
 * <p>Carries the mapping annotations of every persistence backend, since {@code
 * app.persistence.type} chooses between them at runtime; see {@link ScheduledTask} for why one
 * model rather than an entity plus a mapper, and for what {@code @Indexed} means to Redis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = ChatSession.COLLECTION_NAME)
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = ChatSession.COLLECTION_NAME)
@RedisHash(ChatSession.COLLECTION_NAME)
public class ChatSession {
  public static final String COLLECTION_NAME = "bot_chat_sessions";

  /**
   * The {@code conversationId} itself, which is what makes this row an index rather than a table
   * with a key of its own: there is nothing to join, the id is the answer.
   */
  @Id @jakarta.persistence.Id private String id;

  /** findByUserId — the whole reason the model exists. */
  @Indexed private String userId;

  /**
   * The scopes the conversation belongs to, so a listing can be narrowed to a group or a tenant the
   * way the agent's own files and knowledge are. Blank where the surface has no such concept.
   */
  private String groupId;

  private String tenantId;

  /**
   * What the person calls this conversation, or blank where they have not said.
   *
   * <p>Not a cache of the first message — see the class comment. Blank rather than null is the
   * value a cleared name takes, so "never named" and "named, then unnamed again" are one state with
   * one behaviour: both derive. A backend that distinguishes them would have the page draw the same
   * conversation two ways depending on its history.
   */
  private String title;

  private Instant createdAt;

  /**
   * When something last happened in it, so a listing can put the conversation the user was in front
   * of at the top. Not indexed: the ordering is done in memory over one person's own rows, which is
   * a short list, and an index here would be a Redis set rewritten on every single turn.
   */
  private Instant updatedAt;
}
