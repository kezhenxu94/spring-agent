package me.kezhenxu94.springagent.integration.feishu.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.lark.oapi.Client;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.DispatchingUserChatClients;
import me.kezhenxu94.springagent.core.usermodels.ProviderChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moving one of the deployment's own models to another API, end to end: the real registry over a
 * store, the real dispatch and probe, the real card. Only the model answering the probe is a stub.
 */
class FeishuConfigBuiltinProtocolTest {

  private static final String USER = "ou_user";
  private static final String MODEL = "listed-model";
  private static final List<String> PROVIDERS = List.of("openai", "openai-responses");

  private final Map<String, UserModelConfig> rows = new ConcurrentHashMap<>();
  private UserModelRegistry registry;
  private FeishuConfigHandler handler;

  /** A store keeping what it is given, as every backend does. */
  private final class Store implements UserModelConfigRepo {
    @Override
    public UserModelConfig save(final UserModelConfig config) {
      rows.put(config.ownerId() + "/" + config.name(), config);
      return config;
    }

    @Override
    public List<UserModelConfig> findByOwnerId(final String ownerId) {
      return rows.values().stream().filter(row -> ownerId.equals(row.ownerId())).toList();
    }

    @Override
    public Optional<UserModelConfig> findByOwnerIdAndName(final String ownerId, final String name) {
      return Optional.ofNullable(rows.get(ownerId + "/" + name));
    }

    @Override
    public void deleteByOwnerIdAndName(final String ownerId, final String name) {
      rows.remove(ownerId + "/" + name);
    }
  }

  /**
   * One API of the deployment's own provider, as an endpoint serving the model only over the
   * Responses API — and refusing the temperature parameter there, which the deployment's default
   * puts on every request to a row that set none — would answer.
   */
  private record Api(String provider) implements ProviderChatClients {
    @Override
    public ChatClient clientFor(final UserModelConfig config) {
      return answering();
    }

    @Override
    public ChatClient probeClient(
        final UserModelConfig config, final String token, final Map<String, String> headers) {
      if ("openai".equals(provider)) {
        return failing("404: {\"detail\":\"Not Found\"}");
      }
      return me.kezhenxu94.springagent.core.usermodels.Temperatures.omitted(config.temperature())
          ? answering()
          : failing("400 invalid_request_error: \"temperature\" is deprecated for this model");
    }

    private static ChatClient failing(final String message) {
      return ChatClient.builder(
              new ChatModel() {
                @Override
                public ChatResponse call(final Prompt prompt) {
                  throw new UnsupportedOperationException();
                }

                @Override
                public Flux<ChatResponse> stream(final Prompt prompt) {
                  return Flux.error(new IllegalStateException(message));
                }
              })
          .build();
    }

    @Override
    public String configuredEffort() {
      return null;
    }

    @Override
    public String family() {
      return "openai";
    }

    private static ChatClient answering() {
      return ChatClient.builder(
              new ChatModel() {
                @Override
                public ChatResponse call(final Prompt prompt) {
                  throw new UnsupportedOperationException();
                }

                @Override
                public Flux<ChatResponse> stream(final Prompt prompt) {
                  return Flux.just(
                      new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));
                }
              })
          .build();
    }
  }

  @BeforeEach
  void setUp() {
    registry = new UserModelRegistry(new Store(), new AesGcmSealer(KEY, "t"), 3);
    final var chatClients =
        new DispatchingUserChatClients(
            mock(ChatClient.class),
            registry,
            List.of(new Api("openai"), new Api("openai-responses")),
            "openai");
    final var source = new ResourceBundleMessageSource();
    source.setBasename(CoreMessages.BASENAME);
    source.setDefaultEncoding("UTF-8");
    final var properties =
        new SpringAgentProperties(
            new SpringAgentProperties.Ai(
                Set.of(), null, Map.of(), null, null, null, "You are a test.", null, null, null),
            Locale.ENGLISH,
            null,
            null);
    final var probe =
        new UserModelProbe(
            chatClients, new CoreMessages(source, properties), Duration.ofSeconds(10), properties);
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    final var om = JsonMapper.builder().build();
    handler =
        new FeishuConfigHandler(
            mock(Client.class, RETURNS_DEEP_STUBS),
            registry,
            probe,
            mock(BuiltinModels.class),
            chatClients,
            new FeishuConfigForm(om, messages, new ClassPathResource("feishu/config-form.json")),
            om,
            messages,
            new SyncTaskExecutor());
  }

  private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  @DisplayName(
      "a built-in model moved to the Responses API stays there, and the card shows it next time")
  void builtinProtocolSticks() {
    // Switched to on the card, as a user does before changing anything else about it.
    handler.handle(press(FeishuConfigForm.BUILTIN_OPTION + MODEL, "openai"));

    // The card as drawn: the dropdown on the model in use, the protocol changed.
    handler.handle(press(FeishuConfigForm.BUILTIN_OPTION + MODEL, "openai-responses"));

    final var active = registry.active(USER).orElseThrow();
    assertThat(active.name()).isEqualTo(UserModelRegistry.BUILTIN_PREFIX + MODEL);
    assertThat(active.provider()).isEqualTo("openai-responses");
    assertThat(FeishuConfigForm.shownProvider(active, PROVIDERS)).isEqualTo("openai-responses");
    // And it is spoken to the way it answered: over the Responses API, with no temperature.
    assertThat(me.kezhenxu94.springagent.core.usermodels.Temperatures.omitted(active.temperature()))
        .isTrue();

    // And the next press that changes nothing leaves it there.
    handler.handle(press(FeishuConfigForm.BUILTIN_OPTION + MODEL, "openai-responses"));
    assertThat(registry.active(USER).orElseThrow().provider()).isEqualTo("openai-responses");
  }

  private static P2CardActionTrigger press(final String active, final String provider) {
    final var formValue = new HashMap<String, Object>();
    formValue.put(FeishuConfigForm.ACTIVE, active);
    formValue.put(FeishuConfigForm.PROVIDER, provider);
    final var action = new CallBackAction();
    action.setFormValue(formValue);
    final var operator = new CallBackOperator();
    operator.setOpenId(USER);
    final var context = new CallBackContext();
    context.setOpenChatId("oc_chat");
    final var data = new P2CardActionTriggerData();
    data.setAction(action);
    data.setOperator(operator);
    data.setContext(context);
    final var event = new P2CardActionTrigger();
    event.setEvent(data);
    return event;
  }
}
