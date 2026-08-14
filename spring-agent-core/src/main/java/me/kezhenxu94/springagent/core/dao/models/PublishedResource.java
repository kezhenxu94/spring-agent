package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.redis.core.RedisHash;

/**
 * Mapped for every persistence backend; see {@link ScheduledTask} for why. Nothing here is
 * {@code @Indexed}: this is the one model reached only by id.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = PublishedResource.COLLECTION_NAME)
@Entity
@Table(name = PublishedResource.COLLECTION_NAME)
@RedisHash(PublishedResource.COLLECTION_NAME)
public class PublishedResource {
  public static final String COLLECTION_NAME = "bot_published_resources";

  @Id @jakarta.persistence.Id private String id;

  private String ownerId;

  @Enumerated(EnumType.STRING)
  private Visibility visibility;

  private boolean directory;
  private String entryFilename;
  private Instant expiresAt;

  public enum Visibility {
    INTERNAL,
    PUBLIC;

    public static Visibility from(String value) {
      if (value == null) {
        return null;
      }
      for (final var visibility : values()) {
        if (visibility.name().equalsIgnoreCase(value)) {
          return visibility;
        }
      }
      return null;
    }
  }
}
