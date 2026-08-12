package me.kezhenxu94.springagent.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The JDBC branch of {@code app.ai.chat-memory.type}: any database Spring AI ships a chat memory
 * dialect for, the default URL being a local SQLite file.
 *
 * <p>The MongoDB branch stays with Spring AI's own auto-configuration; {@link
 * MongoChatMemoryAutoConfigurationFilter} is what keeps it from adding a second {@link
 * ChatMemoryRepository} next to this one. Spring AI's JDBC auto-configuration is deliberately
 * absent from the classpath (we depend on the plain module, not the starter): it would build its
 * repository from whatever {@link DataSource} it finds, without asking which backend was selected.
 *
 * <p>Ordered before {@link ChatMemoryAutoConfiguration} so its in-memory fallback backs off.
 */
@AutoConfiguration(before = ChatMemoryAutoConfiguration.class)
@ConditionalOnProperty(prefix = "app.ai.chat-memory", name = "type", havingValue = "jdbc")
public class ChatMemoryConfiguration {
  private static final String SCHEMA_LOCATION =
      "org/springframework/ai/chat/memory/repository/jdbc/schema-%s.sql";

  // Unpooled on purpose: chat memory runs a couple of statements per turn, and pulling in a
  // connection pool would mean pulling DataSourceAutoConfiguration in with it.
  @Bean
  DataSource chatMemoryDataSource(final SpringAgentProperties properties) {
    final var jdbc = properties.ai().chatMemory().jdbc();
    createDatabaseFileDirectory(jdbc.url());
    return DataSourceBuilder.create()
        .type(SimpleDriverDataSource.class)
        .driverClassName(DatabaseDriver.fromJdbcUrl(jdbc.url()).getDriverClassName())
        .url(jdbc.url())
        .username(jdbc.username())
        .password(jdbc.password())
        .build();
  }

  @Bean
  ChatMemoryRepository jdbcChatMemoryRepository(
      final SpringAgentProperties properties, final DataSource chatMemoryDataSource) {
    final var jdbc = properties.ai().chatMemory().jdbc();
    if (jdbc.initializeSchema()) {
      final var platform = DatabaseDriver.fromJdbcUrl(jdbc.url()).getId();
      DatabasePopulatorUtils.execute(
          new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_LOCATION.formatted(platform))),
          chatMemoryDataSource);
    }
    return JdbcChatMemoryRepository.builder()
        .jdbcTemplate(new JdbcTemplate(chatMemoryDataSource))
        .dialect(JdbcChatMemoryRepositoryDialect.from(chatMemoryDataSource))
        .build();
  }

  /** File-based databases such as SQLite refuse to create their file under a missing directory. */
  private static void createDatabaseFileDirectory(final String url) {
    final var prefix = "jdbc:sqlite:";
    if (!url.startsWith(prefix)) {
      return;
    }
    final var file = url.substring(prefix.length()).split("\\?", 2)[0];
    if (file.isEmpty() || file.equals(":memory:") || file.startsWith("file:")) {
      return;
    }
    final var directory = Path.of(file).toAbsolutePath().getParent();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Cannot create the directory holding the chat memory database: " + directory, e);
    }
  }
}
