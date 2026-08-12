package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.ws.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessageRepo;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardListener;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuMessageReceiveHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FeishuEventHandler {
  final FeishuProperties feishuProperties;
  final FeishuMessageReceiveHandler feishuMessageReceiveHandler;
  final SpringAgent springAgent;
  final FeishuMessageRepo feishuMessageRepo;
  final FeishuCardListener feishuCardListener;

  @Bean
  public EventDispatcher eventDispatcher() {
    return EventDispatcher.newBuilder(
            feishuProperties.verificationToken(), feishuProperties.encryptKey())
        .onP2MessageReceiveV1(feishuMessageReceiveHandler)
        .onP2MessageReadV1(
            new ImService.P2MessageReadV1Handler() {
              @Override
              public void handle(P2MessageReadV1 event) throws Exception {}
            })
        .onP2CardActionTrigger(
            new P2CardActionTriggerHandler() {
              @Override
              public P2CardActionTriggerResponse handle(P2CardActionTrigger event)
                  throws Exception {
                log.info("Card action trigger: {}", event.getEvent());
                final var values = event.getEvent().getAction().getValue();
                if ("stop".equals(values.get("button"))) {
                  final var messageID = event.getEvent().getContext().getOpenMessageId();
                  // The button only knows the message the card was sent as; the run it belongs to
                  // is whatever the card listener started under it.
                  final var runId = feishuCardListener.runIdFor(messageID);
                  log.info("Stop command received: cardMessageId={}, runId={}", messageID, runId);
                  feishuMessageRepo.updateStatus(messageID, FeishuMessage.Status.CANCELLED);
                  if (runId != null) {
                    springAgent.cancel(runId);
                  }
                }
                return new P2CardActionTriggerResponse();
              }
            })
        .build();
  }

  @Bean(initMethod = "start", destroyMethod = "disconnect")
  public Client client() {
    return new Client.Builder(feishuProperties.appId(), feishuProperties.appSecret())
        .eventHandler(eventDispatcher())
        .build();
  }
}
