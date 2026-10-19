package me.kezhenxu94.springagent.integration.slack.usermodels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.methods.MethodsClient;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.slack.config.SlackMessages;
import me.kezhenxu94.springagent.integration.slack.config.SlackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

/** A submission of the /config form that registers nothing. */
class SlackConfigHandlerTest {

  private static final String USER = "U0USER";

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
  private SlackConfigHandler handler;

  @BeforeEach
  void setUp() {
    final var messages =
        new SlackMessages(
            new SlackProperties("xoxb", "xapp", "U0BOT", "T0TEAM", Locale.ENGLISH, null, null));
    final var chatClients = mock(UserChatClients.class);
    when(chatClients.defaultProvider()).thenReturn("openai");
    when(registry.activate(any(), any())).thenReturn(true);
    when(registry.tokenOf(LUNA)).thenReturn("sk-secret");
    when(registry.headersOf(LUNA)).thenReturn(Map.of());
    handler =
        new SlackConfigHandler(
            mock(MethodsClient.class),
            registry,
            probe,
            mock(BuiltinModels.class),
            chatClients,
            new SlackConfigForm(messages),
            messages,
            new SyncTaskExecutor());
  }

  @Test
  @DisplayName("choosing another protocol for the model in use moves it there once it answers")
  void reprotocolsTheActiveEndpoint() {
    when(registry.active(USER)).thenReturn(Optional.of(LUNA));

    handler.apply(USER, submission("luna", "openai-responses"));

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

    handler.apply(USER, submission("luna", "openai-responses"));

    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName("the protocol already in use is not a change")
  void untouchedProtocolIsNoChange() {
    when(registry.active(USER)).thenReturn(Optional.of(LUNA));

    handler.apply(USER, submission("luna", "openai"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }

  @Test
  @DisplayName("a submission that switched model does not carry the old one's protocol across")
  void switchingIgnoresTheProtocol() {
    final var kimi = LUNA.toBuilder().name("kimi").provider("openai-responses").build();
    // Before the submission the user is on kimi, whose protocol the select was preselected with;
    // the submission switches them to luna.
    when(registry.active(USER)).thenReturn(Optional.of(kimi), Optional.of(LUNA));

    handler.apply(USER, submission("luna", "openai-responses"));

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

    handler.apply(USER, submission(builtin.name(), "openai-responses"));

    verify(probe, never()).check(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }

  private static SlackConfigForm.Submission submission(final String active, final String provider) {
    return new SlackConfigForm.Submission(
        active, null, provider, null, null, null, null, null, null);
  }
}
