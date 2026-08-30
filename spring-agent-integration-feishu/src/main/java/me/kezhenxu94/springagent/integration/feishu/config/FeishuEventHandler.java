package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P1P2PChatCreatedV1;
import com.lark.oapi.service.im.v1.model.P2ChatAccessEventBotP2pChatEnteredV1;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.ws.Client;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.SpringAgent;
import me.kezhenxu94.springagent.integration.feishu.greeting.FeishuGreetings;
import me.kezhenxu94.springagent.integration.feishu.greeting.FeishuSuggestions;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuCardListener;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuMessageReceiveHandler;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuQuestionAnswerHandler;
import me.kezhenxu94.springagent.integration.feishu.handler.FeishuToasts;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FeishuEventHandler {
  final FeishuProperties feishuProperties;
  final FeishuMessages messages;
  final FeishuMessageReceiveHandler feishuMessageReceiveHandler;
  final SpringAgent springAgent;
  final FeishuCardListener feishuCardListener;
  final FeishuQuestionAnswerHandler feishuQuestionAnswerHandler;
  final FeishuGreetings feishuGreetings;
  final FeishuSuggestions feishuSuggestions;

  /**
   * Every event this application does something with. Everything else Feishu pushes is dropped by
   * {@link LenientEventDispatcher} rather than logged as a failure, which is why this list is short
   * and why adding to it is the only thing needed to start handling something new.
   */
  @Bean
  public EventDispatcher eventDispatcher() {
    return new LenientEventDispatcher(
        EventDispatcher.newBuilder(
                feishuProperties.verificationToken(), feishuProperties.encryptKey())
            .onP2MessageReceiveV1(feishuMessageReceiveHandler)
            .onP2MessageReadV1(
                new ImService.P2MessageReadV1Handler() {
                  @Override
                  public void handle(P2MessageReadV1 event) throws Exception {}
                })
            // The first time somebody ever opens a chat with the bot. Documented as a webhook
            // event, so it may never reach a deployment on the long connection — see
            // FeishuGreetings for why nothing depends on it arriving.
            .onP1P2PChatCreatedV1(
                new ImService.P1P2PChatCreatedV1Handler() {
                  @Override
                  public void handle(P1P2PChatCreatedV1 event) {
                    final var created = event.getEvent();
                    feishuGreetings.greet(created.getChatId(), created.getUser().getOpenId());
                  }
                })
            // And every time they open it after that, which is where somebody coming back is told
            // what changed while they were away.
            .onP2ChatAccessEventBotP2pChatEnteredV1(
                new ImService.P2ChatAccessEventBotP2pChatEnteredV1Handler() {
                  @Override
                  public void handle(P2ChatAccessEventBotP2pChatEnteredV1 event) {
                    final var entered = event.getEvent();
                    feishuGreetings.greet(entered.getChatId(), entered.getOperatorId().getOpenId());
                  }
                })
            .onP2CardActionTrigger(
                new P2CardActionTriggerHandler() {
                  @Override
                  public P2CardActionTriggerResponse handle(P2CardActionTrigger event)
                      throws Exception {
                    log.info("Card action trigger: {}", event.getEvent());
                    final var values = event.getEvent().getAction().getValue();
                    // Not every interactive element carries one: a form's inputs report through
                    // form_value instead, and reading a button out of nothing would fail the
                    // callback.
                    if (values == null) {
                      return new P2CardActionTriggerResponse();
                    }
                    final var button = values.get("button");
                    if ("stop".equals(button)) {
                      final var messageID = event.getEvent().getContext().getOpenMessageId();
                      // The button only knows the message the card was sent as; the run it belongs
                      // to
                      // is whatever the card listener started under it.
                      final var run = feishuCardListener.runFor(messageID);
                      final var operator = event.getEvent().getOperator().getOpenId();
                      log.info(
                          "Stop command received: cardMessageId={}, run={}, operator={}",
                          messageID,
                          run,
                          operator);
                      if (run == null) {
                        return new P2CardActionTriggerResponse();
                      }
                      // Cards are shared, so in a group chat everyone can see and press stop. Only
                      // the
                      // person the run is answering gets to end it.
                      if (!Objects.equals(operator, run.userId())) {
                        log.info(
                            "Stop for run {} from {}, who is not {}",
                            run.runId(),
                            operator,
                            run.userId());
                        return FeishuToasts.toast("warning", messages.get("card-stop-not-yours"));
                      }
                      springAgent.cancel(run.runId());
                    } else if (FeishuQuestionAnswerHandler.ACTION.equals(button)) {
                      return feishuQuestionAnswerHandler.handle(event);
                    } else if (FeishuSuggestions.ACTION.equals(button)) {
                      return feishuSuggestions.handle(event);
                    }
                    return new P2CardActionTriggerResponse();
                  }
                }));
  }

  @Bean(initMethod = "start", destroyMethod = "disconnect")
  public Client client() {
    return new Client.Builder(feishuProperties.appId(), feishuProperties.appSecret())
        .domain(feishuProperties.baseUrl().getUrl())
        .eventHandler(eventDispatcher())
        .build();
  }
}
