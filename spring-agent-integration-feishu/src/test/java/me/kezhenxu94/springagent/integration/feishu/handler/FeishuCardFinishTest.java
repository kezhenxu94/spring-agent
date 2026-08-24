package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.DeleteCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardResp;
import java.nio.file.Path;
import java.util.Locale;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** What a card stops being when the run behind it ends. */
@ExtendWith(MockitoExtension.class)
class FeishuCardFinishTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @TempDir Path userHomeRoot;

  private final JsonMapper om = new JsonMapper();

  private FeishuCard card;

  @BeforeEach
  void setUp() throws Exception {
    final var deleted = new DeleteCardElementResp();
    deleted.setCode(0);
    when(feishu.cardkit().v1().cardElement().delete(any(DeleteCardElementReq.class)))
        .thenReturn(deleted);
    final var settings = new SettingsCardResp();
    settings.setCode(0);
    when(feishu.cardkit().v1().card().settings(any(SettingsCardReq.class))).thenReturn(settings);

    final var messages =
        new FeishuMessages(
            new FeishuProperties(null, null, null, null, null, null, null, Locale.ENGLISH, null));
    card = new FeishuCard(feishu, "card-1", null, new UserHome(userHomeRoot), messages);
  }

  @Test
  @DisplayName("the stop button goes, there being no run left to stop")
  void theStopButtonIsRemoved() throws Exception {
    card.finish();

    final var captor = ArgumentCaptor.forClass(DeleteCardElementReq.class);
    verify(feishu.cardkit().v1().cardElement()).delete(captor.capture());
    assertThat(captor.getValue().getElementId()).isEqualTo("stop");
  }

  @Test
  @DisplayName("streaming mode closes, so the card stops accepting what it is streamed")
  void streamingModeIsClosed() throws Exception {
    card.finish();

    assertThat(om.readTree(settingsSent()).path("config").path("streaming_mode").asBoolean())
        .isFalse();
  }

  private String settingsSent() throws Exception {
    final var captor = ArgumentCaptor.forClass(SettingsCardReq.class);
    verify(feishu.cardkit().v1().card()).settings(captor.capture());
    return captor.getValue().getSettingsCardReqBody().getSettings();
  }
}
