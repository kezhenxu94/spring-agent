package me.kezhenxu94.springagent.integration.slack.handler;

/**
 * The stop button's identity on the wire.
 *
 * <p>Its own class so that the updater that draws the button and the handler that answers the press
 * cannot disagree about what it is called. Bolt selects a handler by {@code action_id}, so this
 * string is the whole of the coupling between the two.
 */
public final class SlackStopButton {

  /**
   * Prefixed {@code sa_} like every other action this application registers, so that {@code
   * SlackEventHandler}'s one pattern can claim them all without claiming somebody else's.
   */
  public static final String ACTION_ID = "sa_stop";

  private SlackStopButton() {}
}
