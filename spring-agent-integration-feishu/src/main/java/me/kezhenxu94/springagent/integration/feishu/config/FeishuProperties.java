package me.kezhenxu94.springagent.integration.feishu.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.SneakyThrows;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feishu tenant and application credentials. */
@ConfigurationProperties(prefix = "app.feishu")
public record FeishuProperties(
    String encryptKey,
    String tenantId,
    String tenantDomain,
    String appId,
    String appSecret,
    String botOpenId,
    String verificationToken) {

  @SneakyThrows
  public byte[] encryptKeyBytes() {
    final var digest = MessageDigest.getInstance("SHA-256");
    return digest.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
  }
}
