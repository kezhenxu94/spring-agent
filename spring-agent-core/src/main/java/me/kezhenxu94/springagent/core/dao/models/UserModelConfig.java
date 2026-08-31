package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

/**
 * One OpenAI-compatible endpoint a user has registered for their own chats. Mapped for every
 * persistence backend; see {@link ScheduledTask} for why.
 *
 * <p>{@link #activated} is which one a user's runs actually go to, and switching writes it in one
 * order on purpose: every other row of theirs is cleared first, and only then is the new one set.
 * There is no transaction spanning all three backends, so a switch interrupted halfway has to leave
 * something sane behind — this way that is no activated row at all, which reads as the
 * application's own model. The other order could leave two rows claiming to be active, which is a
 * question with no right answer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@CompoundIndex(name = "owner_model_unique", def = "{'ownerId': 1, 'name': 1}", unique = true)
@Document(collection = UserModelConfig.COLLECTION_NAME)
@Entity
@Table(
    name = UserModelConfig.COLLECTION_NAME,
    // The JPA counterpart of the @CompoundIndex above.
    uniqueConstraints =
        @UniqueConstraint(
            name = "owner_model_unique",
            columnNames = {"ownerId", "name"}))
@RedisHash(UserModelConfig.COLLECTION_NAME)
public class UserModelConfig {
  public static final String COLLECTION_NAME = "user_models";

  /**
   * {@code ownerId + ':' + name}, so that registering the same name twice replaces the row rather
   * than racing the unique constraint — which Redis cannot enforce at all, its secondary indexes
   * having no notion of uniqueness. Built by {@link #idFor}; never assign it by hand.
   */
  @Id @jakarta.persistence.Id private String id;

  // findByOwnerId, findByOwnerIdAndName.
  @Indexed private String ownerId;

  @Indexed private String name;

  /** The endpoint's base URL, as {@code spring.ai.openai.base-url} would give it. */
  private String baseUrl;

  /** The model to ask for, as the endpoint names it. */
  private String model;

  /**
   * The API token, sealed by {@code AesGcmSealer} — never the plaintext, and never handed back to
   * the model or shown in a listing. Long enough for the expansion base64 and the nonce add on top
   * of a token that may itself be a JWT.
   */
  @Column(length = 131072)
  private String apiKeyCipher;

  /**
   * Whether this is the endpoint the owner's runs go to. At most one row per owner has it, and none
   * having it means the application's own model — see the class javadoc for why that is the failure
   * mode worth having.
   *
   * <p>Deliberately not {@code @Indexed}: it is only ever read after {@code findByOwnerId}, which
   * is indexed already, and a Redis index over a boolean would be one set holding half the table.
   */
  private boolean activated;

  private Instant updatedAt;

  public static String idFor(final String ownerId, final String name) {
    return ownerId + ':' + name;
  }
}
