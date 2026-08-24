package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.core.enums.BaseUrlEnum;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feishu tenant and application credentials.
 *
 * @param baseUrl which of the two products this app is registered with: {@code FeiShu} (the Chinese
 *     product, open.feishu.cn) or {@code LarkSuite} (the international one, open.larksuite.com). An
 *     app id/secret pair is only valid against the product it was created on, so this must match
 *     where the app was registered. Defaults to {@code FeiShu}.
 * @param locale which language the cards speak. Defaults to the host's, so setting it is for a
 *     workspace whose language differs from the machine the agent runs on. See {@link
 *     FeishuMessages} for what it selects.
 * @param requestTimeout how long any one call to Feishu may take. Stated because the SDK's own
 *     default is no timeout at all — {@code OKHttps.create(0, MILLISECONDS)}, which OkHttp reads as
 *     "wait for ever" — and a card update that never returns is not a lost card but a stuck run:
 *     every writer to a card holds the same lock while it calls, and a subagent reports itself
 *     finished to its parent's card before the run that started it is released. One unanswered HTTP
 *     call was therefore enough to hang a whole turn with nothing logged. Defaults to {@link
 *     #DEFAULT_REQUEST_TIMEOUT}.
 */
@ConfigurationProperties(prefix = "app.feishu")
public record FeishuProperties(
    String encryptKey,
    String tenantId,
    String tenantDomain,
    String appId,
    String appSecret,
    String botOpenId,
    String verificationToken,
    Locale locale,
    BaseUrlEnum baseUrl,
    Duration requestTimeout) {

  /** Generous for a card update, which is a small write to a nearby service. */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  public FeishuProperties {
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    }
    if (baseUrl == null) {
      baseUrl = BaseUrlEnum.FeiShu;
    }
  }

  @SneakyThrows
  public byte[] encryptKeyBytes() {
    final var digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
  }
}
