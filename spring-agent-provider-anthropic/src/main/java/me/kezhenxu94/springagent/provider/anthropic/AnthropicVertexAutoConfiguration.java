package me.kezhenxu94.springagent.provider.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.vertex.backends.VertexBackend;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The chat model, where a Google Cloud project is what serves Claude.
 *
 * <p>On the other backend this class contributes nothing and Spring AI's own {@code
 * AnthropicChatAutoConfiguration} builds the model, which is the whole of the plain path — there is
 * no code of ours on it, deliberately. Here there has to be, because {@code AnthropicSetup} hard
 * wires {@code AnthropicBackend} and Spring AI exposes no seam for a different one.
 *
 * <p><b>How the two are kept from both winning.</b> Spring AI's {@code anthropicChatModel} bean is
 * {@code @ConditionalOnMissingBean}, and this configuration declares itself {@code beforeName} it,
 * so where the Vertex backend is selected ours is registered first and theirs backs off. Where it
 * is not, nothing here is registered at all and theirs is the only one. One bean either way, and
 * the decision is a single property rather than an ordering nobody can see.
 *
 * <p>{@code beforeName} rather than {@code before}: naming the class would load it, and a string
 * keeps this module working if Spring AI relocates it — which it has done within this generation
 * for the Google GenAI auto-configurations. {@code AnthropicVertexWiringTest} asserts the name
 * still resolves, so a rename fails the build rather than silently producing two models.
 *
 * <p>The bean's return type is {@code AnthropicChatModel} and not {@code ChatModel}, and that is
 * load-bearing: {@code @ConditionalOnMissingBean} on Spring AI's method infers the type from
 * <em>its</em> return type, so a definition typed as the interface would not satisfy it and both
 * would be built.
 *
 * <p>Its own class rather than a bean method on {@link AnthropicProviderAutoConfiguration}, so that
 * the {@code @ConditionalOnBean(AnthropicChatModel.class)} beans there are answered against a
 * configuration already visited rather than against a sibling method in the same class.
 */
@AutoConfiguration(
    beforeName =
        "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration")
@ConditionalOnClass({AnthropicClient.class, VertexBackend.class})
@EnableConfigurationProperties({
  AnthropicProperties.class,
  AnthropicConnectionProperties.class,
  AnthropicChatProperties.class
})
// Both halves of the decision, and neither is matchIfMissing. Spring AI's own condition on the
// first key is, which is how an application naming no provider ends up with every provider; this
// one is stricter on purpose, so that an unset switch never produces a Vertex model by accident.
@ConditionalOnProperty(name = AnthropicProperties.CHAT_PROVIDER_PROPERTY, havingValue = "anthropic")
@ConditionalOnProperty(
    name = AnthropicProperties.BACKEND_PROPERTY,
    havingValue = AnthropicProperties.BACKEND_VERTEX)
public class AnthropicVertexAutoConfiguration {

  /**
   * Both of the SDK's clients, and the model built on them.
   *
   * <p>The pairing is the trap — see {@link VertexAnthropicClients} — and passing one without the
   * other leaves streaming pointed at {@code api.anthropic.com}.
   */
  @Bean
  @ConditionalOnMissingBean
  AnthropicChatModel anthropicChatModel(
      final AnthropicProperties properties,
      final AnthropicConnectionProperties connectionProperties,
      final AnthropicChatProperties chatProperties,
      final ToolCallingManager toolCallingManager,
      final ObjectProvider<ObservationRegistry> observationRegistry,
      final ObjectProvider<MeterRegistry> meterRegistry) {

    final var observations = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    final var meters = meterRegistry.getIfAvailable();

    final var clients =
        VertexAnthropicClients.create(
            properties.vertex(),
            connectionProperties.getTimeout(),
            connectionProperties.getMaxRetries(),
            connectionProperties.getCustomHeaders(),
            observations,
            meters);

    return AnthropicChatModel.builder()
        .anthropicClient(clients.sync())
        // Not optional. Without it AnthropicChatModel builds its own asynchronous client from
        // AnthropicSetup, and every streaming call — which is what a run uses — goes to Anthropic
        // rather than to Vertex.
        .anthropicClientAsync(clients.async())
        .options(chatProperties.toOptions())
        // The context's own manager, so a run here offers the endpoint the same tool definitions
        // every other run does. Spring AI's own auto-configuration passes it the same way; the
        // method is deprecated in favour of a ToolCallingAdvisor and will need revisiting when that
        // lands, but until then this is the only seam by which core's tool rewrites reach the wire.
        .toolCallingManager(toolCallingManager)
        .observationRegistry(observations)
        .meterRegistry(meters)
        // No httpClientBuilderCustomizers here: the builder refuses them beside a pre-built client,
        // because by then the HTTP layer already exists. The error-body interceptor is attached
        // inside VertexAnthropicClients instead.
        .build();
  }
}
