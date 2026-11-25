package me.kezhenxu94.springagent.integration.telegram.usermodels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.usermodels.BuiltinModels;
import me.kezhenxu94.springagent.core.usermodels.UserChatClients;
import me.kezhenxu94.springagent.core.usermodels.UserModelProbe;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.telegram.api.TelegramBotApi;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramMessages;
import me.kezhenxu94.springagent.integration.telegram.config.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

/** A {@code /config provider=…} for one of the deployment's own models. */
class TelegramConfigHandlerTest {

  private static final String USER = "42";

  private static final UserModelConfig BUILTIN =
      UserModelConfig.builder()
          .ownerId(USER)
          .name(UserModelRegistry.BUILTIN_PREFIX + "listed-model")
          .model("listed-model")
          .activated(true)
          .build();

  private final UserModelRegistry registry = mock(UserModelRegistry.class);
  private final UserModelProbe probe = mock(UserModelProbe.class);
  private final UserChatClients chatClients = mock(UserChatClients.class);
  private TelegramConfigHandler handler;

  @BeforeEach
  void setUp() {
    // Answering, unless a test says otherwise: what a protocol change is probed with.
    when(probe.probe(any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(new UserModelProbe.Result(null, false));
    final var messages =
        new TelegramMessages(
            new TelegramProperties(
                "1:t", null, Locale.ENGLISH, null, null, null, null, null, null));
    when(chatClients.defaultProvider()).thenReturn("openai");
    when(registry.active(USER)).thenReturn(Optional.of(BUILTIN));
    handler =
        new TelegramConfigHandler(
            mock(TelegramBotApi.class),
            registry,
            probe,
            mock(BuiltinModels.class),
            chatClients,
            new TelegramConfigForm(messages),
            messages,
            new SyncTaskExecutor());
  }

  private static TelegramConfigForm.Submission provider(final String provider) {
    return new TelegramConfigForm.Submission(
        false, null, provider, null, null, null, null, null, null);
  }

  @Test
  @DisplayName(
      "a built-in model moves to another API of the deployment's own provider, probed with the"
          + " deployment's endpoint and credential")
  void builtinMovesWithinTheDeploymentsProvider() {
    when(chatClients.servesBuiltin("openai-responses")).thenReturn(true);

    handler.apply(USER, USER, provider("openai-responses"));

    verify(probe)
        .probe("openai-responses", null, "listed-model", null, null, null, false, Map.of());
    verify(registry).setProvider(USER, BUILTIN.name(), "openai-responses");
  }

  @Test
  @DisplayName("and is not moved to another provider, which needs an endpoint of one's own")
  void builtinStaysWithTheDeploymentsProvider() {
    final var said = handler.apply(USER, USER, provider("anthropic"));

    assertThat(said).contains("an endpoint of your own");
    verify(probe, never()).probe(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    verify(registry, never()).setProvider(any(), any(), any());
  }
}
