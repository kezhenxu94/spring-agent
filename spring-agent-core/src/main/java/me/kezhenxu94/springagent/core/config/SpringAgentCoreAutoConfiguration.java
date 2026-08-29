package me.kezhenxu94.springagent.core.config;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.aot.AgentToolsRuntimeHints;
import me.kezhenxu94.springagent.core.aot.CoreMessagesRuntimeHints;
import me.kezhenxu94.springagent.core.aot.OpenAiSdkRuntimeHints;
import me.kezhenxu94.springagent.core.aot.StoragePropertiesRuntimeHints;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageProperties;
import me.kezhenxu94.springagent.core.storage.FileSystemStorageService;
import me.kezhenxu94.springagent.core.storage.StorageProperties;
import me.kezhenxu94.springagent.core.tools.i18n.LocalizingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.i18n.ModuleToolTexts;
import me.kezhenxu94.springagent.core.tools.i18n.ToolTexts;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallbackResolver;
import me.kezhenxu94.springagent.core.tools.interceptors.InterceptingToolCallingManager;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.core.tools.mcp.McpProperties;
import me.kezhenxu94.springagent.core.tools.mcp.McpStreamableHttpHeadersProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
  McpStreamableHttpHeadersProperties.class,
  // Bound here rather than by the backend modules: whichever of them is on the classpath reads it,
  // and neither is guaranteed to be.
  PersistenceProperties.class,
  // Same reasoning: the settings of a particular shell are bound by the module implementing it.
  ShellToolsProperties.class,
  // Spring AI's own, bound again here so that the tool-call limits below do not depend on which
  // auto-configuration was sorted first. Registering it twice is a no-op — a configuration
  // properties bean is registered under a conventional name and skipped if that name is taken.
  ToolCallingProperties.class
})
@ImportRuntimeHints({
  AgentToolsRuntimeHints.class,
  CoreMessagesRuntimeHints.class,
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
  @ConfigurationProperties("app.storage")
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
  BatchingStrategy embeddingBatchingStrategy(
      @Value("${app.ai.embedding.batch-size:10}") final int batchSize) {
    // Spring AI's default TokenCountBatchingStrategy only limits batches by token count
    // (8191), so dozens of short tool descriptions fit in a single embeddings.create
    // call; DashScope's OpenAI-compatible endpoint rejects batches over ~20 rows
    // regardless of token count ("batch size is invalid, it should not be larger than
    // 20"). Cap by row count instead, and default well under that limit — a provider
    // that allows larger batches is the reason this is a property.
    //
    // Raising it is only half of what makes a cold index quick, and the smaller half:
    // the batches are embedded one after another, so what a few hundred tool
    // descriptions cost is decided by how many of these calls run at once. That is
    // app.ai.embedding.concurrency, and ParallelAddVectorStore.
    return new FixedSizeBatchingStrategy(batchSize);
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

  /**
   * Core's own tool translations. A bean rather than a field of the manager so that a module adds
   * its own by contributing another, which is how the two thirds of the tools that belong to
   * spring-agent-integration-feishu are translated without core seeing its resources.
   */
  @Bean
  ToolTexts coreToolTexts(final SpringAgentProperties properties) {
    return new ModuleToolTexts("core/tools", LocalizedPrompt.TOOLS_LOCATION, properties.locale());
  }

  /**
   * The manager a run's tool calls go through, in the order the three layers have to sit in.
   *
   * <p>Localization outermost of the two decorators, because what it rewrites is the definition
   * list on its way out to the model and it must be the last word on it. Interception outermost
   * overall, because it is what the run's own behaviour hangs off — the mid-turn user message and
   * the wrapping of each callback — and none of that concerns the definitions.
   *
   * <p>The innermost manager is built here rather than taken from Spring AI's own
   * auto-configuration, which is why {@code spring.ai.tools.limits.*} has to be applied by hand
   * below: this bean is the one that wins — both declarations are {@code @ConditionalOnMissingBean}
   * and this class sorts first — so anything upstream reads out of those properties and into its
   * builder is read nowhere unless it is read here. Leaving them unread would have made every one
   * of them a setting that binds, documents itself and does nothing, {@link ToolCallingDefaults}
   * included.
   */
  @Bean
  @ConditionalOnMissingBean
  ToolCallingManager toolCallingManager(
      final ToolCallbackResolver toolCallbackResolver,
      final List<ToolCallInterceptor> interceptors,
      final List<ToolTexts> toolTexts,
      final ToolCallingProperties toolCallingProperties) {
    final var builder =
        DefaultToolCallingManager.builder()
            .toolCallbackResolver(
                new InterceptingToolCallbackResolver(toolCallbackResolver, interceptors));
    applyLimits(builder, toolCallingProperties.getLimits());
    final var defaultManager = builder.build();
    final var localizing = new LocalizingToolCallingManager(defaultManager, toolTexts);
    return new InterceptingToolCallingManager(localizing, interceptors);
  }

  /**
   * Translates {@code spring.ai.tools.limits.*} onto the builder exactly as Spring AI's own
   * auto-configuration does, {@code -1} included: it means no limit rather than a limit of minus
   * one, and there is no builder setter to hand it to — a separate call turns the limit off.
   *
   * <p>A null is a limit the deployment blanked out, which asks for the builder's own default, so
   * it is left alone rather than turned into a number here.
   */
  private static void applyLimits(
      final DefaultToolCallingManager.Builder builder, final ToolCallingProperties.Limits limits) {
    final var unlimited = -1;

    final var maxCallsPerToolDefault = limits.getMaxCallsPerToolDefault();
    if (maxCallsPerToolDefault != null) {
      if (maxCallsPerToolDefault == unlimited) {
        builder.unlimitedCallsPerTool();
      } else {
        builder.maxCallsPerTool(maxCallsPerToolDefault);
      }
    }

    limits
        .getMaxCallsPerTool()
        .forEach(
            (tool, maxCalls) -> {
              if (maxCalls == unlimited) {
                builder.excludeToolFromLimit(tool);
              } else {
                builder.maxCallsPerTool(tool, maxCalls);
              }
            });

    limits.getExcludedTools().forEach(builder::excludeToolFromLimit);

    final var maxTotalToolCalls = limits.getMaxTotalToolCalls();
    if (maxTotalToolCalls != null) {
      if (maxTotalToolCalls == unlimited) {
        builder.unlimitedTotalToolCalls();
      } else {
        builder.maxTotalToolCalls(maxTotalToolCalls);
      }
    }

    builder.onLimitExceeded(limits.getOnLimitExceeded());
  }

  /**
   * Puts the provider's own words back into the log when it rejects a request.
   *
   * <p>Spring AI applies every customizer bean of this type to the OkHttp client behind each OpenAI
   * model, which is the only seam that still sees the response bytes — see {@link
   * OpenAiErrorBodyLoggingInterceptor} for why they are otherwise unrecoverable. Not behind a
   * property: it only ever fires on a request that already failed, and a 4xx nobody can explain is
   * the reason it exists.
   */
  @Bean
  @ConditionalOnMissingBean(name = "openAiErrorBodyLoggingCustomizer")
  OpenAiHttpClientBuilderCustomizer openAiErrorBodyLoggingCustomizer() {
    final var interceptor = new OpenAiErrorBodyLoggingInterceptor();
    return builder -> builder.interceptor(interceptor);
  }

  // Name-based: two ChatClient beans here, so a type-based condition would have the first
  // suppress the second.
  @Bean
  @ConditionalOnMissingBean(name = "chatClient")
  ChatClient chatClient(final ChatClient.Builder builder) {
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean(name = "visionChatClient")
  @Qualifier("vision")
  ChatClient visionChatClient(
      final SpringAgentProperties appConfiguration,
      // Spring AI applies these to the models its own auto-configuration builds; this model is
      // built here, so it has to ask for them itself or it would be the one endpoint whose
      // rejections stay unreadable — and it is a gateway, which is where unreadable ones come from.
      final List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
    final var vision = appConfiguration.dashscope().vision();
    final var chatModel =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .baseUrl(vision.baseUrl())
                    .apiKey(vision.apiKey())
                    .model(vision.model())
                    .build())
            .httpClientBuilderCustomizers(httpClientCustomizers)
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
