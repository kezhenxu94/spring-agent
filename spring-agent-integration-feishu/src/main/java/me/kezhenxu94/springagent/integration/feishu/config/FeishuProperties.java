package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.core.enums.BaseUrlEnum;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feishu tenant and application credentials.
 *
 * @param baseUrl which of the two products this app is registered with: {@code FeiShu} (the Chinese
 *     product, open.feishu.cn) or {@code LarkSuite} (the international one, open.larksuite.com). An
 *     app id/secret pair is only valid against the product it was created on, so this must match
 *     where the app was registered. Defaults to {@code FeiShu}.
 * @param botOpenId the bot's own open_id, which is an identity two decisions turn on: whether a
 *     group message mentioned the bot rather than somebody else ({@code
 *     FeishuMessageReceiveHandler}), and whether a run asking to reach a chat belongs to the bot
 *     rather than to a person ({@link
 *     me.kezhenxu94.springagent.integration.feishu.tools.FeishuChatAccess}) — the second is what
 *     lets a run owned by the agent itself, an event triage run, write to the chats the bot is in,
 *     since a member list never holds a bot.
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
 * @param observedChatIds the group chats whose messages are reported to {@code EventIntake} even
 *     though nobody addressed the bot in them, so that the agent can watch a conversation and later
 *     decide whether it has anything worth saying. Empty by default, and that default is the
 *     feature being off: every message in every group the bot sits in becoming a stored row, and in
 *     time something a model is shown, is a volume and a privacy decision only whoever runs the
 *     deployment can make — naming a chat here is that decision, one chat at a time. Nothing is
 *     observed regardless where no {@code EventIntake} implementation is on the classpath. See
 *     {@link me.kezhenxu94.springagent.integration.feishu.handler.FeishuChatObservations}.
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
    Duration requestTimeout,
    Set<String> observedChatIds) {

  /** Generous for a card update, which is a small write to a nearby service. */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  public FeishuProperties {
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    }
    if (baseUrl == null) {
      baseUrl = BaseUrlEnum.FeiShu;
    }
    if (observedChatIds == null) {
      observedChatIds = Set.of();
    }
  }

  @SneakyThrows
  public byte[] encryptKeyBytes() {
    final var digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
  }
}
