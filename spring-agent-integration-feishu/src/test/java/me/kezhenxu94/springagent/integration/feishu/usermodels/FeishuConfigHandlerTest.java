package me.kezhenxu94.springagent.integration.feishu.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackContext;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/** A press of the /config card's save button that registers nothing. */
class FeishuConfigHandlerTest {

  private static final String USER = "ou_user";

  private static final UserModelConfig LUNA =
      UserModelConfig.builder()
          .ownerId(USER)
          .name("luna")
          .provider("openai")
          .baseUrl("https://gw/v1")
          .model("gpt-5.6-luna")
          .reasoningEffort("high")
          .activated(true)
          .build();

  private final UserModelRegistry registry = mock(UserModelRegistry.class);
  private final UserModelProbe probe = mock(UserModelProbe.class);
  private UserChatClients chatClients;
  private FeishuConfigHandler handler;

  @BeforeEach
  void setUp() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    final var om = JsonMapper.builder().build();
    chatClients = mock(UserChatClients.class);
    when(chatClients.defaultProvider()).thenReturn("openai");
    when(chatClients.providers()).thenReturn(List.of("openai", "openai-responses", "anthropic"));
    when(registry.activate(any(), any())).thenReturn(true);
    when(registry.tokenOf(LUNA)).thenReturn("sk-secret");
    when(registry.headersOf(LUNA)).thenReturn(Map.of());
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

  @Test
  @DisplayName("choosing another protocol for the model in use moves it there once it answers")
  void reprotocolsTheActiveEndpoint() {
    when(registry.active(USER)).thenReturn(Optional.of(LUNA));

    handler.handle(press("luna", "openai-responses"));

    // Probed with what is stored, since nothing was retyped: the token, the effort and all.
    verify(probe)
        .check(
            "openai-responses",
            "https://gw/v1",
            "gpt-5.6-luna",
            "sk-secret",
            "high",
            null,
            false,
            Map.of());
    verify(registry).setProvider(USER, "luna", "openai-responses");
  }

  @Test
  @DisplayName("and leaves it where it was when the endpoint does not answer in the new one")
  void keepsTheProtocolWhenTheProbeFails() {
    when(registry.active(USER)).thenReturn(Optional.of(LUNA));
    when(probe.check(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn("404 Not Found");

    handler.handle(press("luna", "openai-responses"));

    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName("the protocol already in use is not a change")
  void untouchedProtocolIsNoChange() {
    when(registry.active(USER)).thenReturn(Optional.of(LUNA));

    handler.handle(press("luna", "openai"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName(
      "a press that switched model and left the protocol as shown does not carry the old one's"
          + " protocol onto the new one")
  void switchingIgnoresTheProtocol() {
    final var kimi = LUNA.toBuilder().name("kimi").provider("openai-responses").build();
    // Before the press the user is on kimi, whose protocol the select was preselected with; the
    // press switches them to luna.
    when(registry.active(USER)).thenReturn(Optional.of(kimi), Optional.of(LUNA));

    handler.handle(press("luna", "openai-responses"));

    verify(registry, never()).setProvider(any(), any(), any());
  }

  /** The endpoint the user is on when the form is drawn. */
  private static final UserModelConfig FIRST =
      UserModelConfig.builder()
          .ownerId(USER)
          .name("first")
          .provider("openai")
          .baseUrl("https://first/v1")
          .model("first-model")
          .reasoningEffort("high")
          .activated(true)
          .build();

  /** A second endpoint of the user's, which the press switches to from {@link #FIRST}. */
  private static final UserModelConfig OTHER =
      UserModelConfig.builder()
          .ownerId(USER)
          .name("other")
          .provider("openai")
          .baseUrl("https://other/v1")
          .model("other-model")
          .reasoningEffort("low")
          .activated(true)
          .build();

  @Test
  @DisplayName(
      "a press that switched model and changed the protocol moves the model switched to, once it"
          + " answers there")
  void switchingWithAChangedProtocol() {
    when(registry.tokenOf(OTHER)).thenReturn("sk-other");
    when(registry.headersOf(OTHER)).thenReturn(Map.of());
    // Drawn for first, whose openai the select showed; the press picks other and anthropic.
    when(registry.active(USER)).thenReturn(Optional.of(FIRST), Optional.of(OTHER));

    handler.handle(press("other", "anthropic"));

    verify(probe)
        .check(
            "anthropic",
            "https://other/v1",
            "other-model",
            "sk-other",
            "low",
            null,
            false,
            Map.of());
    verify(registry).setProvider(USER, "other", "anthropic");
  }

  @Test
  @DisplayName(
      "a press that switched model with the protocol left as shown keeps the new model's own,"
          + " though it differs")
  void switchingKeepsTheNewModelsProtocol() {
    final var responses = OTHER.toBuilder().provider("openai-responses").build();
    // The select showed first's openai and was left alone; other speaks openai-responses.
    when(registry.active(USER)).thenReturn(Optional.of(FIRST), Optional.of(responses));

    handler.handle(press("other", "openai"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName(
      "the effort works the same way: left as shown on a switch it keeps the new model's, changed"
          + " it is applied")
  void switchingAndTheEffort() {
    when(registry.active(USER)).thenReturn(Optional.of(FIRST), Optional.of(OTHER));

    handler.handle(press("other", "openai", "high"));
    verify(registry, never()).setActiveEffort(any(), any());

    when(registry.active(USER)).thenReturn(Optional.of(FIRST), Optional.of(OTHER));
    when(registry.setActiveEffort(USER, "medium")).thenReturn(OTHER);

    handler.handle(press("other", "openai", "medium"));
    verify(registry).setActiveEffort(USER, "medium");
  }

  /** One of the deployment's own models, chosen off its list, with no protocol stored yet. */
  private static final UserModelConfig BUILTIN =
      UserModelConfig.builder()
          .ownerId(USER)
          .name(UserModelRegistry.BUILTIN_PREFIX + "listed-model")
          .model("listed-model")
          .activated(true)
          .build();

  @Test
  @DisplayName(
      "a built-in model moved to another API of the deployment's own provider is probed with the"
          + " deployment's endpoint and credential, then stored, and shown next time")
  void builtinMovesWithinTheDeploymentsProvider() {
    final var builtin = BUILTIN;
    when(chatClients.servesBuiltin("openai-responses")).thenReturn(true);
    when(registry.active(USER)).thenReturn(Optional.of(builtin));

    handler.handle(press(builtin.name(), "openai-responses"));

    // No base URL and no token: the client borrows the deployment's, as every run on it does.
    verify(probe)
        .check("openai-responses", null, "listed-model", null, null, null, false, Map.of());
    verify(registry).setProvider(USER, builtin.name(), "openai-responses");
    assertThat(
            FeishuConfigForm.shownProvider(
                builtin.toBuilder().provider("openai-responses").build(),
                List.of("openai", "openai-responses", "anthropic")))
        .isEqualTo("openai-responses");
  }

  @Test
  @DisplayName(
      "a built-in model is not moved to another provider, which needs an endpoint of one's own")
  void builtinStaysWithTheDeploymentsProvider() {
    final var builtin = BUILTIN;
    when(registry.active(USER)).thenReturn(Optional.of(builtin));

    handler.handle(press(builtin.name(), "anthropic"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName("one of the deployment's own models has no endpoint of the user's to move")
  void builtinModelsKeepTheDeploymentsProtocol() {
    final var builtin =
        UserModelConfig.builder()
            .ownerId(USER)
            .name(UserModelRegistry.BUILTIN_PREFIX + "gpt-5.6-luna")
            .model("gpt-5.6-luna")
            .activated(true)
            .build();
    when(registry.active(USER)).thenReturn(Optional.of(builtin));

    handler.handle(press(builtin.name(), "openai-responses"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), eq("openai-responses"));
  }

  private static P2CardActionTrigger press(final String active, final String provider) {
    return press(active, provider, null);
  }

  private static P2CardActionTrigger press(
      final String active, final String provider, final String effort) {
    final var formValue = new HashMap<String, Object>();
    formValue.put(FeishuConfigForm.ACTIVE, active);
    formValue.put(FeishuConfigForm.PROVIDER, provider);
    if (effort != null) {
      formValue.put(FeishuConfigForm.EFFORT, effort);
    }
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
