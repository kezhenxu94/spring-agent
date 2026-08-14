package me.kezhenxu94.springagent.integration.feishu.handler;

import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;

/**
 * The reply to a card action that has nothing to change on the card, only something to say to the
 * person who pressed it. Shared because every control on a card refuses in the same way.
 */
public final class FeishuToasts {

  private FeishuToasts() {}

  public static P2CardActionTriggerResponse toast(final String type, final String content) {
    final var toast = new CallBackToast();
    toast.setType(type);
    toast.setContent(content);
    final var response = new P2CardActionTriggerResponse();
    response.setToast(toast);
    return response;
  }
}
