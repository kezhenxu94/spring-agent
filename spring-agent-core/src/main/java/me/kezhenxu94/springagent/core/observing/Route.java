package me.kezhenxu94.springagent.core.observing;

import lombok.Builder;

/**
 * Where a run about something may talk, and in whose scope. The identity half of an {@link
 * Observation}, and the same four values a deployment configures for a source that has no chat of
 * its own.
 *
 * <p>A type rather than four fields repeated in both places, because the two are the same question
 * asked of a surface and of a configuration file, and because they have to be resolved against each
 * other: a chat observation knows the chat it came from, while an alert knows nothing and takes
 * what it was given. {@link #orElse} is that resolution, and it is deliberately whole-object.
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

  /**
   * This route where it names a chat, and {@code fallback} where it does not.
   *
   * <p>All or nothing rather than field by field. A chat observation that named its chat must not
   * pick up a tenant or a group from configuration meant for a different source — that would scope
   * the run, and so the workspace and the knowledge it retrieves, to somewhere it did not come
   * from.
   */
  public Route orElse(final Route fallback) {
    return isEmpty() && fallback != null ? fallback : this;
  }
}
