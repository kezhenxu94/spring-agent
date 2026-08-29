package me.kezhenxu94.springagent.core.observing;

import lombok.Builder;

/**
 * Where a run about something may talk, and in whose scope. The identity half of an {@link
 * Observation}, and the same four values a deployment configures when it wants to be told
 * something.
 *
 * <p>A type rather than four fields repeated in both places, because the two are the same question
 * asked of a surface and of a configuration file.
 *
 * <p>An observation's route is its own and is never filled in from configuration. A chat message
 * knows the chat it came from and a run about it talks there; an alert knows nowhere, and a run
 * about it reaches people through what it was told to do rather than through an address it was
 * handed. Where a deployment configures a route it is for its own purposes — {@code
 * app.events.sources.&lt;name&gt;.route} is where to report that a triage run failed — and that
 * route is never merged into an observation.
 *
 * <p>Not merged into {@code Observation#payloadJson}. The payload is written by whoever caused the
 * event and is stored and shown as evidence; this is our own determination of who a run acts as.
 * Keeping them separate types is what makes it impossible for something in a payload to be read as
 * routing.
 *
 * @param chatId where to talk; blank for an observation that knows nowhere
 * @param chatType free-form, as elsewhere in this codebase
 * @param groupId opaque group identifier, blank where there is no group
 * @param tenantId opaque tenant identifier, blank where the surface has no tenant concept
 */
@Builder
public record Route(String chatId, String chatType, String groupId, String tenantId) {

  /** What a source that knows nowhere to talk reports. */
  public static final Route NONE = new Route(null, null, null, null);

  /** Whether this says nothing about where to talk. */
  public boolean isEmpty() {
    return chatId == null || chatId.isBlank();
  }
}
