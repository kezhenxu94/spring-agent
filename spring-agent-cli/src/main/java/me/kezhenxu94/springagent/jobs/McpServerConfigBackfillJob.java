package me.kezhenxu94.springagent.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.dao.models.McpServerConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * One-off backfill: sets {@code version} on existing {@link McpServerConfig} records that predate
 * the field, so {@link me.kezhenxu94.springagent.tools.McpClientFactory} doesn't need to guess a
 * default at read time forever. {@code title}, {@code description} and {@code websiteUrl} are left
 * untouched — they have no prior implicit value worth backfilling.
 */
@Slf4j
@Component
@Profile("mcp-server-config-backfill")
@RequiredArgsConstructor
public class McpServerConfigBackfillJob implements CommandLineRunner {
  private final MongoTemplate mongoTemplate;

  @Override
  public void run(String... args) {
    final var query =
        Query.query(
            new Criteria()
                .orOperator(
                    Criteria.where("version").exists(false), Criteria.where("version").is(null)));
    final var update = new Update().set("version", McpServerConfig.DEFAULT_VERSION);
    final var result = mongoTemplate.updateMulti(query, update, McpServerConfig.class);
    log.info(
        "Backfilled version={} on {} existing McpServerConfig records",
        McpServerConfig.DEFAULT_VERSION,
        result.getModifiedCount());
  }
}
