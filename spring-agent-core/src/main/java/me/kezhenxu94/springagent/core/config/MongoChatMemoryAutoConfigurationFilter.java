package me.kezhenxu94.springagent.core.config;

import java.util.Set;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties.Ai.ChatMemory.Type;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Keeps Spring AI's MongoDB chat memory out of the context unless {@code app.ai.chat-memory.type}
 * asks for it.
 *
 * <p>{@code MongoChatMemoryAutoConfiguration} guards its bean with
 * {@code @ConditionalOnMissingBean} on the <em>concrete</em> {@code MongoChatMemoryRepository}
 * type, so it would not back off in front of the JDBC repository from {@link
 * ChatMemoryConfiguration} — the context would end up with two {@code ChatMemoryRepository} beans
 * and no way to choose. Filtering the auto-configuration out is what a {@code
 * spring.autoconfigure.exclude} entry would do, except it follows the property instead of having to
 * be repeated by every application using this module.
 */
public class MongoChatMemoryAutoConfigurationFilter
    implements AutoConfigurationImportFilter, EnvironmentAware {

  private static final String PACKAGE =
      "org.springframework.ai.model.chat.memory.repository.mongo.autoconfigure.";
  private static final Set<String> MONGO_CHAT_MEMORY_AUTO_CONFIGURATIONS =
      Set.of(
          PACKAGE + "MongoChatMemoryAutoConfiguration",
          PACKAGE + "MongoChatMemoryIndexCreatorAutoConfiguration");

  private Environment environment;

  @Override
  public void setEnvironment(final Environment environment) {
    this.environment = environment;
  }

  @Override
  public boolean[] match(
      final String[] autoConfigurationClasses, final AutoConfigurationMetadata metadata) {
    final var type = environment.getProperty("app.ai.chat-memory.type", Type.MONGODB.name());
    final var mongoSelected = Type.MONGODB.name().equalsIgnoreCase(type.trim());
    final var matches = new boolean[autoConfigurationClasses.length];
    for (int i = 0; i < matches.length; i++) {
      final var candidate = autoConfigurationClasses[i];
      matches[i] =
          mongoSelected
              || candidate == null
              || !MONGO_CHAT_MEMORY_AUTO_CONFIGURATIONS.contains(candidate);
    }
    return matches;
  }
}
