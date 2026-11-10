package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.List;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.websocket.config.WebMessages;
import me.kezhenxu94.springagent.integration.websocket.run.DeviceTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The device tools a request declared, as the callbacks the run it starts is offered — or a 400
 * saying what is wrong with them.
 *
 * <p>One place for both endpoints that start a run, sending a message and answering a question,
 * because a run started by answering is as much the phone's turn as one started by typing: the
 * phone's tools have to come along, and checked by the same rules.
 *
 * <p>Call it before anything the request changes — a question marked answered, a form superseded —
 * so that a malformed declaration costs the app a retry and not the state it was acting on.
 */
@Component
@RequiredArgsConstructor
class DeviceToolDeclarations {

  private final DeviceTools deviceTools;
  private final WebMessages messages;

  List<ToolCallback> callbacks(
      final String requestId, final String userId, final List<DeviceTools.Spec> specs) {
    try {
      // The sender's language, resolved now: the sentences the model reads on a timeout are
      // written later, on the run's thread, which has no request to resolve one from.
      return deviceTools.callbacks(requestId, userId, specs, messages.locale());
    } catch (final IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }
}
