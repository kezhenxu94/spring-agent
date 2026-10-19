package me.kezhenxu94.springagent.integration.feishu.usermodels;

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
  private FeishuConfigHandler handler;

  @BeforeEach
  void setUp() {
    final var messages =
        new FeishuMessages(
            new FeishuProperties(
                null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null));
    final var om = JsonMapper.builder().build();
    final var chatClients = mock(UserChatClients.class);
    when(chatClients.defaultProvider()).thenReturn("openai");
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
  @DisplayName("a press that switched model does not carry the old one's protocol onto the new one")
  void switchingIgnoresTheProtocol() {
    final var kimi = LUNA.toBuilder().name("kimi").provider("openai-responses").build();
    // Before the press the user is on kimi, whose protocol the select was preselected with; the
    // press switches them to luna.
    when(registry.active(USER)).thenReturn(Optional.of(kimi), Optional.of(LUNA));

    handler.handle(press("luna", "openai-responses"));

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
