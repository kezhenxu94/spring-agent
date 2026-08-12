package me.kezhenxu94.springagent.integration.feishu;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@RegisterReflectionForBinding({
  TenantAccessToken.class,
  TenantAccessToken.TenantAccessTokenBuilder.class
})
public class FeishuTenantAccessTokenService {
  private static final String TENANT_ACCESS_TOKEN_URL =
      "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/";

  private final RestTemplate restTemplate;
  private final FeishuProperties feishuProperties;

  public TenantAccessToken tenantAccessToken() {
    final var headers = new HttpHeaders();
    headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
    final var httpEntity =
        new HttpEntity<>(
            Map.of(
                "app_id", feishuProperties.appId(),
                "app_secret", feishuProperties.appSecret()),
            headers);

    final var response =
        restTemplate.postForEntity(TENANT_ACCESS_TOKEN_URL, httpEntity, TenantAccessToken.class);
    if (response.getStatusCode() != HttpStatus.OK || response.getBody().code() != 0) {
      log.error("Failed to get tenant access token: {}", response);
      throw new IllegalStateException("Failed to get tenant access token");
    }
    return response.getBody();
  }
}
