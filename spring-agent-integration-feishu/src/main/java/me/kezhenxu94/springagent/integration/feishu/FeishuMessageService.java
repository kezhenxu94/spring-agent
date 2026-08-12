package me.kezhenxu94.springagent.integration.feishu;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.integration.feishu.model.FeishuResponse;
import me.kezhenxu94.springagent.integration.feishu.model.Message;
import me.kezhenxu94.springagent.integration.feishu.model.SendMessageResponseDTO;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
@RegisterReflectionForBinding({Message.class})
public class FeishuMessageService {
  private static final String SEND_MESSAGE_URL =
      "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type={receive_id_type}";
  private static final String REPLY_MESSAGE_URL =
      "https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/reply";

  private final RestTemplate restTemplate;
  private final FeishuTenantAccessTokenService tenantAccessTokenService;

  public SendMessageResponseDTO sendMessage(final Message message) {
    final var token = tenantAccessTokenService.tenantAccessToken();
    final var headers = new HttpHeaders();
    headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token.tenantAccessToken());
    headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

    final var httpEntity = new HttpEntity<>(message, headers);
    final var response =
        restTemplate.exchange(
            SEND_MESSAGE_URL,
            HttpMethod.POST,
            httpEntity,
            new ParameterizedTypeReference<FeishuResponse<SendMessageResponseDTO>>() {},
            message.receiveType());
    if (response.getBody() == null || response.getBody().code() != 0) {
      log.error("Failed to set sheet values: {}", response);
      throw new IllegalStateException("Failed to set sheet values");
    }
    return response.getBody().data();
  }

  public void replyMessage(final String messageId, final Message message) {
    final var token = tenantAccessTokenService.tenantAccessToken();
    final var headers = new HttpHeaders();
    headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token.tenantAccessToken());
    headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

    final var httpEntity = new HttpEntity<>(message, headers);
    restTemplate.postForEntity(REPLY_MESSAGE_URL, httpEntity, FeishuResponse.class, messageId);
  }
}
