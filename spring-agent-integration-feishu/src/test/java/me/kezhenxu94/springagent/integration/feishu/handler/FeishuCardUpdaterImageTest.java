package me.kezhenxu94.springagent.integration.feishu.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import com.lark.oapi.service.im.v1.model.CreateImageRespBody;
import java.nio.file.Files;
import java.nio.file.Path;
import me.kezhenxu94.springagent.core.tools.UserHome;
import me.kezhenxu94.springagent.core.tools.UserWorkspaceFactory;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties.CardText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A card can only show images the tenant has uploaded, so tools hand back local paths and the
 * updater uploads them. What that upload must not do is reach outside the user it answers for, or
 * repeat itself on every streaming tick.
 */
@ExtendWith(MockitoExtension.class)
class FeishuCardUpdaterImageTest {

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  private Client feishu;

  @Mock private RestTemplate restTemplate;
  @Mock private UserWorkspaceFactory userWorkspaceFactory;

  @TempDir Path userHomeRoot;
  @TempDir Path elsewhere;

  private FeishuCardUpdater updater;

  private static CreateImageResp uploaded(final String imageKey) {
    final var body = new CreateImageRespBody();
    body.setImageKey(imageKey);
    final var resp = new CreateImageResp();
    resp.setCode(0);
    resp.setData(body);
    return resp;
  }

  @BeforeEach
  void setUp() {
    lenient()
        .when(userWorkspaceFactory.forOwner(anyString()))
        .thenReturn(new UserHome(userHomeRoot));
    updater =
        new FeishuCardUpdater(
            feishu,
            new JsonMapper(),
            "card-1",
            "ou_user",
            restTemplate,
            userWorkspaceFactory,
            null,
            new CardText(null, null, null, null, null, null, "[image unavailable]", null, null));
  }

  @Test
  @DisplayName("an image under the user's home is uploaded once, however many ticks render it")
  void localImageIsUploadedOncePerRun() throws Exception {
    final var image = Files.write(userHomeRoot.resolve("generated.png"), new byte[] {1, 2, 3});
    when(feishu.im().v1().image().create(any(CreateImageReq.class)))
        .thenReturn(uploaded("img_v3_key"));

    final var markdown = "here it is ![a cat](" + image + ")";
    assertThat(updater.reuploadImages(markdown)).isEqualTo("here it is ![a cat](img_v3_key)");
    assertThat(updater.reuploadImages(markdown + " and more text"))
        .isEqualTo("here it is ![a cat](img_v3_key) and more text");

    verify(feishu.im().v1().image(), times(1)).create(any(CreateImageReq.class));
  }

  @Test
  @DisplayName("a file:// URL, as GenerateImage returns, is uploaded like a plain path")
  void fileUrlIsUploaded() throws Exception {
    final var image = Files.write(userHomeRoot.resolve("generated.png"), new byte[] {1, 2, 3});
    when(feishu.im().v1().image().create(any(CreateImageReq.class)))
        .thenReturn(uploaded("img_v3_key"));

    assertThat(updater.reuploadImages("![a cat](" + image.toUri() + ")"))
        .isEqualTo("![a cat](img_v3_key)");
  }

  @Test
  @DisplayName("an image outside the user's home is never read, let alone uploaded")
  void imageOutsideUserHomeIsRejected() throws Exception {
    final var image = Files.write(elsewhere.resolve("someone-elses.png"), new byte[] {1, 2, 3});

    assertThat(updater.reuploadImages("![secret](" + image.toUri() + ")"))
        .isEqualTo("[image unavailable]");

    verify(feishu.im().v1().image(), never()).create(any(CreateImageReq.class));
  }

  @Test
  @DisplayName("a remote image is downloaded and uploaded")
  void remoteImageIsUploaded() throws Exception {
    when(restTemplate.getForObject("https://example.com/a.png", byte[].class))
        .thenReturn(new byte[] {1, 2, 3});
    when(feishu.im().v1().image().create(any(CreateImageReq.class)))
        .thenReturn(uploaded("img_v3_remote"));

    assertThat(updater.reuploadImages("![a cat](https://example.com/a.png)"))
        .isEqualTo("![a cat](img_v3_remote)");
  }

  @Test
  @DisplayName("a target that is neither a local path nor a URL is left as written")
  void unknownTargetsAreLeftAlone() {
    assertThat(updater.reuploadImages("![a cat](img_v3_already_a_key)"))
        .isEqualTo("![a cat](img_v3_already_a_key)");

    verify(restTemplate, never()).getForObject(anyString(), eq(byte[].class));
  }
}
