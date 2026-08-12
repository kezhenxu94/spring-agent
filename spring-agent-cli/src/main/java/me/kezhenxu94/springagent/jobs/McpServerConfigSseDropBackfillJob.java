package me.kezhenxu94.springagent.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.dao.models.McpServerConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-off backfill: SSE support was dropped from {@link McpServerConfig.Transport}, so any
 * pre-existing record with {@code transport: "SSE"} would otherwise fail to deserialize the moment
 * the enum constant disappears, breaking every read (list/add/share) for its owner. Rewrites those
 * records to {@code STREAMABLE_HTTP} and disables them, since they can no longer actually be
 * reached over the transport they were registered with — the owner must re-add the server once it
 * exposes a streamable HTTP endpoint.
 *
 * <p>Deployment order: run this once <strong>before</strong> the code change that drops {@code SSE}
 * from {@link McpServerConfig.Transport} goes live.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpServerConfigSseDropBackfillJob implements CommandLineRunner {
  private final MongoTemplate mongoTemplate;

  @Override
  public void run(String... args) {
    final var query = Query.query(Criteria.where("transport").is("SSE"));
    final var update = new Update().set("transport", "STREAMABLE_HTTP").set("enabled", false);
    final var result = mongoTemplate.updateMulti(query, update, McpServerConfig.class);
    log.info(
        "Disabled and converted {} existing McpServerConfig records off dropped SSE transport",
        result.getModifiedCount());
  }
}
