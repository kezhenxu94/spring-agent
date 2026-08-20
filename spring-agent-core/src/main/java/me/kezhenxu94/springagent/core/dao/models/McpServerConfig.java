package me.kezhenxu94.springagent.core.dao.models;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import me.kezhenxu94.springagent.core.dao.StringMapJsonConverter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

/**
 * Mapped for every persistence backend; see {@link ScheduledTask} for why.
 *
 * <p>The {@code ownerId + name} uniqueness below is declared twice, once for each backend that can
 * enforce it. Redis is not one of them — its secondary indexes cannot express uniqueness — so on
 * that backend the constraint is advisory: saving twice under the same owner and name with
 * different ids yields two records rather than an error. The callers reach servers through {@code
 * findByOwnerIdAndName}, which would then return an arbitrary one of them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@CompoundIndex(name = "owner_name_unique", def = "{'ownerId': 1, 'name': 1}", unique = true)
@Document(collection = McpServerConfig.COLLECTION_NAME)
@Entity
@Table(
    name = McpServerConfig.COLLECTION_NAME,
    // The JPA counterpart of the @CompoundIndex above.
    uniqueConstraints =
        @UniqueConstraint(
            name = "owner_name_unique",
            columnNames = {"ownerId", "name"}))
@RedisHash(McpServerConfig.COLLECTION_NAME)
public class McpServerConfig {
  public static final String COLLECTION_NAME = "mcp_servers";
  public static final String DEFAULT_VERSION = "1.0.0";

  /**
   * A {@link #sharedWith} entry meaning "every caller," not one specific open_id/chat_id. {@code
   * findAccessibleTo}'s {@code identifiers} argument only needs to include this value for a config
   * carrying it to become visible to everyone, on every backend — {@code sharedWith} is already
   * queried by plain equality/{@code IN}, so this sentinel needs no query changes to work.
   */
  public static final String SHARED_WITH_ALL = "*";

  @Id @jakarta.persistence.Id private String id;

  // findByOwnerId, findByOwnerIdAndName.
  @Indexed private String ownerId;

  @Indexed private String name;

  @Enumerated(EnumType.STRING)
  private Transport transport;

  private String url;

  // A JSON column rather than a table: headers are opaque to every query, so a join table would buy
  // nothing. Contrast sharedWith below, which is queried and therefore mapped relationally.
  @Convert(converter = StringMapJsonConverter.class)
  @Column(length = 4096)
  private Map<String, String> headers;

  /**
   * Display title reported to the MCP server as {@code clientInfo.title}; falls back to {@link
   * #name}.
   */
  private String title;

  /**
   * Client version reported to the MCP server as {@code clientInfo.version}; falls back to {@link
   * #DEFAULT_VERSION}.
   */
  private String version;

  private String description;
  private String websiteUrl;

  @Builder.Default private boolean enabled = true;

  /**
   * Feishu {@code open_id}s (individual users) and/or {@code chat_id}s (group chats) that the owner
   * has granted access to this server, in addition to the owner themselves.
   *
   * <p>Mapped as a collection table rather than JSON because {@code findBySharedWithIn} and {@code
   * findAccessibleTo} query it; that keeps both as plain JPQL instead of database-specific JSON
   * functions. Eager because every read of a server config is followed by an access check, so lazy
   * loading would only add an N+1.
   *
   * <p>{@code @Indexed} on a collection indexes each element separately, one Redis set per
   * identifier. That is what lets the Redis backend serve the two queries above as indexed reads
   * rather than a scan of every stored server.
   */
  @Builder.Default
  @Indexed
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = COLLECTION_NAME + "_shared_with",
      joinColumns = @JoinColumn(name = "mcp_server_id"))
  @Column(name = "identifier")
  private List<String> sharedWith = new ArrayList<>();

  /**
   * SSE was dropped (the upstream MCP SDK deprecated it); only streamable HTTP remains, for now.
   */
  public enum Transport {
    STREAMABLE_HTTP,
  }
}
