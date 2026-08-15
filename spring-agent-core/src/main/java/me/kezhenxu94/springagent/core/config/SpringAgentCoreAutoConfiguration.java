package me.kezhenxu94.springagent.core.config;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.aot.AgentToolsRuntimeHints;
import me.kezhenxu94.springagent.core.aot.OpenAiSdkRuntimeHints;
import me.kezhenxu94.springagent.core.aot.StoragePropertiesRuntimeHints;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageService;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallbackResolver;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.core.tools.mcp.McpProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestTemplate;

/**
 * Wires the agent runtime: infrastructure beans plus every {@code @Component} in this module.
 *
 * <p>Component scanning from an auto-configuration is unusual, but the alternative is declaring
 * some fifty tool and service beans by hand. It is safe because this module owns that package
 * exclusively.
 *
 * <p>Repository registration lives in the spring-agent-persistence-* modules instead, because which
 * repositories exist depends on {@code app.persistence.type} — see {@link
 * ConditionalOnPersistenceBackend}.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.core",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = SpringAgentCoreAutoConfiguration.class))
@EnableConfigurationProperties({
  SpringAgentProperties.class,
  McpProperties.class,
  // Bound here rather than by the backend modules: whichever of them is on the classpath reads it,
  // and neither is guaranteed to be.
  PersistenceProperties.class,
  // Same reasoning: the settings of a particular shell are bound by the module implementing it.
  ShellToolsProperties.class
})
@ImportRuntimeHints({
  AgentToolsRuntimeHints.class,
  OpenAiSdkRuntimeHints.class,
  StoragePropertiesRuntimeHints.class
})
public class SpringAgentCoreAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  RestTemplate restTemplate() {
    return new RestTemplate();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConfigurationProperties("storage")
  @Validated
  public StorageProperties storageProperties() {
    return FileSystemStorageProperties.builder().build();
  }

  // Injected, not calling storageProperties(): @AutoConfiguration implies proxyBeanMethods =
  // false, so a direct call would return an unbound instance.
  @Bean
  @ConditionalOnMissingBean
  public FileSystemStorageService storageService(final StorageProperties storageProperties) {
    final var b = new FileSystemStorageService(storageProperties);
    b.init();
    return b;
  }

  @Bean
  @ConditionalOnMissingBean
  BatchingStrategy embeddingBatchingStrategy() {
    // Spring AI's default TokenCountBatchingStrategy only limits batches by token count
    // (8191), so dozens of short tool descriptions fit in a single embeddings.create
    // call; DashScope's OpenAI-compatible endpoint rejects batches over ~20 rows
    // regardless of token count ("batch size is invalid, it should not be larger than
    // 20"). Cap by row count instead, well under that limit.
    return new FixedSizeBatchingStrategy(10);
  }

  @RequiredArgsConstructor
  static class FixedSizeBatchingStrategy implements BatchingStrategy {
    private final int maxBatchSize;

    @Override
    public List<List<Document>> batch(final List<Document> documents) {
      final var batches = new ArrayList<List<Document>>();
      for (int i = 0; i < documents.size(); i += maxBatchSize) {
        batches.add(documents.subList(i, Math.min(i + maxBatchSize, documents.size())));
      }
      return batches;
    }
  }

  @Bean
  @ConditionalOnMissingBean
  ToolCallingManager toolCallingManager(
      final ToolCallbackResolver toolCallbackResolver,
      final List<ToolCallInterceptor> interceptors) {
    final var defaultManager =
        DefaultToolCallingManager.builder()
            .toolCallbackResolver(
                new InterceptingToolCallbackResolver(toolCallbackResolver, interceptors))
            .build();
    return new InterceptingToolCallingManager(defaultManager, interceptors);
  }

  // Name-based: two ChatClient beans here, so a type-based condition would have the first
  // suppress the second.
  @Bean
  @ConditionalOnMissingBean(name = "chatClient")
  ChatClient chatClient(final ChatClient.Builder builder) {
    // spring-ai 2.0.1-SNAPSHOT always sends a "strict" field on tool function definitions; when
    // unset it serializes as an explicit `"strict": null`, which OpenAI treats as opting into
    // strict schema validation (requiring `required` to list every property). Force false to
    // keep the lenient validation our @ToolParam(required = false) tools rely on.
    // TODO: remove once spring-ai fixes strict defaulting for optional @ToolParam and we're back
    // on a released (non-SNAPSHOT) version.
    return builder.defaultOptions(OpenAiChatOptions.builder().strict(false)).build();
  }

  @Bean
  @ConditionalOnMissingBean(name = "visionChatClient")
  @Qualifier("vision")
  ChatClient visionChatClient(final SpringAgentProperties appConfiguration) {
    final var vision = appConfiguration.dashscope().vision();
    final var chatModel =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(vision.baseUrl())
                    .apiKey(vision.apiKey())
                    .model(vision.model())
                    .build())
            .build();
    return ChatClient.builder(chatModel).build();
  }

  @Bean
  @ConditionalOnMissingBean
  ThreadPoolTaskScheduler taskScheduler() {
    final var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("scheduled-task-");
    return scheduler;
  }
}
