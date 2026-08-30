package me.kezhenxu94.springagent.appweb.web;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.appweb.run.RunEvent;
import me.kezhenxu94.springagent.appweb.run.RunJournal;
import me.kezhenxu94.springagent.appweb.run.RunJournals;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Watching a run happen.
 *
 * <p>The connection is a reader of {@code RunJournal} and nothing more. Opening one starts nothing;
 * closing one stops nothing. Every path out of here — the browser navigating away, a proxy timing
 * the connection out, an exception on the socket — does exactly one thing, which is to detach the
 * reader. That is the whole of what makes closing a tab safe.
 *
 * <p>Reconnection is the protocol's rather than hand-rolled: every event is sent with its sequence
 * number as the SSE id, so a browser whose connection drops reconnects on its own with {@code
 * Last-Event-ID} and is replayed from exactly where it stopped. {@code ?from=} is the same thing
 * for a page that has just loaded and knows what it already has.
 */
@Slf4j
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunStreamController {

  /**
   * Longer than any run should take, because the timeout is not a safety net here — it is only what
   * makes the browser reconnect, and it reconnects seamlessly. Left finite rather than infinite so
   * a connection to a process that has forgotten about it eventually goes away.
   */
  private static final long TIMEOUT_MILLIS = 30 * 60 * 1000L;

  private final RunJournals journals;

  @GetMapping(value = "/{requestId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @AuthenticationPrincipal final OAuth2User principal,
      @PathVariable final String requestId,
      @RequestParam(name = "from", required = false) final Long from,
      @RequestHeader(name = "Last-Event-ID", required = false) final Long lastEventId) {

    final var user = ChatController.user(principal);
    final var journal =
        journals
            .byRequestId(requestId)
            .filter(it -> user.id().equals(it.userId()))
            // Gone, rather than never existed: a journal is evicted a while after its run ends, and
            // the browser's answer to this is to fall back to the transcript, which is not lost.
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    final var emitter = new SseEmitter(TIMEOUT_MILLIS);
    // Last-Event-ID wins: it is the browser's own account of what it received, and it is set on a
    // reconnect the page did not initiate and so could not put a ?from= on.
    final var cursor = lastEventId != null ? lastEventId : from == null ? 0L : from;

    final var reader = new EmitterReader(emitter, requestId);
    // Detach and nothing else. Not cancel: the run belongs to SpringAgent, and a browser closing is
    // not an instruction about it.
    emitter.onCompletion(() -> journal.detach(reader));
    emitter.onTimeout(
        () -> {
          journal.detach(reader);
          emitter.complete();
        });
    emitter.onError(e -> journal.detach(reader));

    try {
      final var stillLive = journal.attach(reader, cursor);
      if (!stillLive) {
        // Everything replayed and the run is over. Completing here rather than holding the
        // connection open saves the browser a reconnect to a stream that would never speak.
        emitter.complete();
      }
    } catch (final Exception e) {
      emitter.completeWithError(e);
    }
    return emitter;
  }

  /** The journal's view of one open connection. */
  @RequiredArgsConstructor
  private static final class EmitterReader implements RunJournal.Reader {

    private final SseEmitter emitter;
    private final String requestId;

    @Override
    public void onEvent(final RunEvent event) {
      try {
        emitter.send(
            SseEmitter.event()
                .id(String.valueOf(event.seq()))
                .name(event.type())
                .data(event.data(), MediaType.APPLICATION_JSON));
      } catch (final IOException | IllegalStateException e) {
        // The ordinary end of a connection, not a failure worth a stack trace: the reader has gone.
        // Rethrown so the journal drops it — see RunJournal.append.
        log.debug("Reader of run {} has gone: {}", requestId, e.toString());
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void onClosed() {
      emitter.complete();
    }
  }
}
