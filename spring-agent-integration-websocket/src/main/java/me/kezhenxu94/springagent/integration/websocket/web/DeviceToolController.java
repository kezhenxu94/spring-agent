package me.kezhenxu94.springagent.integration.websocket.web;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.integration.websocket.run.DeviceTools;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where a device claims a tool call it read in a run's journal, and reports what running it came
 * to. See {@link DeviceTools} for why it takes two steps.
 *
 * <p>Both ids are UUIDs this application minted, so they are path variables — the argument the
 * conversation endpoints make. Another person's run is a 404, the answer the stop button gives, for
 * the reason it gives: telling somebody that a run they do not own exists is itself a leak.
 */
@RestController
@RequestMapping("/api/runs/{requestId}/device-tools/{callId}")
@RequiredArgsConstructor
public class DeviceToolController {

  private final DeviceTools deviceTools;

  @PostMapping("/claim")
  public Map<String, Object> claim(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String requestId,
      @PathVariable final String callId) {
    final var user = ChatController.user(principal);
    answer(deviceTools.claim(requestId, callId, user.id()));
    return Map.of("claimed", true);
  }

  /**
   * @param output what the tool produced, handed to the model as it stands — or, with {@code
   *     error}, what went wrong, which the model is told was a failure
   */
  public record Result(String output, boolean error) {}

  @PostMapping("/result")
  public ResponseEntity<Void> result(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String requestId,
      @PathVariable final String callId,
      @RequestBody final Result body) {
    final var user = ChatController.user(principal);
    answer(
        deviceTools.answer(
            requestId,
            callId,
            user.id(),
            body == null ? null : body.output(),
            body != null && body.error()));
    return ResponseEntity.noContent().build();
  }

  private static void answer(final DeviceTools.Outcome outcome) {
    switch (outcome) {
      case ACCEPTED -> {}
      case CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT);
      case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
