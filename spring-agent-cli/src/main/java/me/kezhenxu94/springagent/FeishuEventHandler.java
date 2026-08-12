package me.kezhenxu94.springagent;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.ws.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.bot.feishu.FeishuProperties;
import me.kezhenxu94.springagent.dao.models.FeishuMessage;
import me.kezhenxu94.springagent.handlers.FeishuMessageReceiveHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FeishuEventHandler {
  final FeishuProperties feishuProperties;
  final FeishuMessageReceiveHandler feishuMessageReceiveHandler;
  final MongoTemplate mongoTemplate;

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
                  log.info("Stop command received");
                  final var messageID = event.getEvent().getContext().getOpenMessageId();
                  mongoTemplate.updateFirst(
                      new Query(Criteria.where("id").is(messageID)),
                      new Update().set("status", FeishuMessage.Status.CANCELLED),
                      FeishuMessage.class);
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
