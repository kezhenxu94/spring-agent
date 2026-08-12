package me.kezhenxu94.springagent.dao.models;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Document(collection = PublishedResource.COLLECTION_NAME)
public class PublishedResource {
  public static final String COLLECTION_NAME = "bot_published_resources";

  @Id private String id;
  private String ownerId;
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
