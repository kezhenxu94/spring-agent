package me.kezhenxu94.springagent.core.dao.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@CompoundIndex(name = "owner_name_unique", def = "{'ownerId': 1, 'name': 1}", unique = true)
@Document(collection = McpServerConfig.COLLECTION_NAME)
public class McpServerConfig {
  public static final String COLLECTION_NAME = "mcp_servers";
  public static final String DEFAULT_VERSION = "1.0.0";

  @Id private String id;

  private String ownerId;
  private String name;
  private Transport transport;
  private String url;
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
   */
  @Builder.Default private List<String> sharedWith = new ArrayList<>();

  /**
   * SSE was dropped (the upstream MCP SDK deprecated it); only streamable HTTP remains, for now.
   */
  public enum Transport {
    STREAMABLE_HTTP,
  }
}
