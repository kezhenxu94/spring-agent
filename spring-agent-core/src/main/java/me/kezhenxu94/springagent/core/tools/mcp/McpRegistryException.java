package me.kezhenxu94.springagent.core.tools.mcp;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * A registry operation that could not be done, and which of the ways it could not be.
 *
 * <p>A reason and its arguments rather than a finished sentence, because the two callers have to
 * say different things about the same refusal: {@code McpServerManagementTools} turns one into
 * prose a model reads and acts on, and the browser's {@code McpController} into an HTTP status plus
 * a sentence a person reads. A registry that returned a localized string would be a registry that
 * had already decided which of those its caller was.
 *
 * <p>Its own type rather than {@code IllegalArgumentException} for the reason {@code
 * SkillAccessDenied} is: a caller that cannot tell "you do not own that" from "that server did not
 * answer" ends up reporting one as the other, and a wrong credential reads like an outage.
 */
@Getter
@Accessors(fluent = true)
public class McpRegistryException extends RuntimeException {

  /**
   * Why an operation was refused.
   *
   * <p>Each value is a different thing the person or the model can do about it, which is the only
   * test for whether two of these should be one. {@link #INVALID} and {@link #UNREACHABLE} look
   * alike and are not: the first is a URL that was never going to work and the second is one that
   * may work in a minute.
   */
  public enum Reason {
    /** The URL or the tool prefix is not one this runtime accepts; {@code detail} says which. */
    INVALID,
    /** Another server this caller can reach already names its tools that way. */
    PREFIX_TAKEN,
    /** The server did not answer the registration probe. Nothing was stored. */
    UNREACHABLE,
    /** It answered, and the row could not be written. */
    SAVE_FAILED,
    /** No server of that name is registered to this caller. */
    UNKNOWN,
    /**
     * That name belongs to this application's own configuration, not to the caller.
     *
     * <p>Told apart from {@link #UNKNOWN} on purpose: the answer "no such server" for one whose
     * tools the model can see itself calling reads as "it does not exist", and invites it to
     * register one of its own under the same name.
     */
    APPLICATION_CONFIGURED,
    /** The share being added is already there. */
    ALREADY_SHARED,
    /** The share being revoked was not there. */
    NOT_SHARED,
  }

  private final transient Reason reason;

  /**
   * What the reason needs to be said out loud, in the order its sentence uses them: the server
   * name, the target of a share, the prefix and the server holding it, or a message from whatever
   * refused. Never anything the caller did not already have — see {@code SkillAccessDenied} on why
   * a refusal does not repeat internal detail back out.
   */
  private final transient Object[] arguments;

  public McpRegistryException(final Reason reason, final Object... arguments) {
    super(reason.name());
    this.reason = reason;
    this.arguments = arguments == null ? new Object[0] : arguments.clone();
  }

  public Object[] arguments() {
    return arguments.clone();
  }
}
