package me.kezhenxu94.springagent.core.notify;

import me.kezhenxu94.springagent.core.observing.Route;

/**
 * Says something to a {@link Route} without a run behind it — the one way anything here reaches a
 * person other than by the agent choosing to.
 *
 * <p>Core ships no implementation, and a deployment without one simply has nowhere to send these,
 * which is a supported configuration rather than a broken one. A surface implements it as a
 * {@code @Bean}; whoever wants to send something takes an {@code ObjectProvider<Notifier>} and does
 * nothing when there is none. Exactly one exists in a running deployment — a chat surface is
 * singular here — so this doubles as the answer to "which chat platform is this deployment's".
 *
 * <p><b>Why this exists at all, when a run can already talk.</b> Everything the agent says reaches
 * somebody because a surface's {@code AgentResponseListener} renders the run it belongs to. That
 * covers every case but two, and both are a message with no run of its own to be rendered by:
 *
 * <ul>
 *   <li>a run that failed, or never started. A background run is not rendered anywhere, so the
 *       failure of an unattended run — event triage, a scheduled task — is visible only in a log
 *       nobody is reading at the time. It follows that an implementation must not need a model, an
 *       agent or a live request to do its work: the failure most worth reporting is the one where
 *       the model is what broke.
 *   <li>a run whose answer belongs on a chat it did not happen on. Somebody continuing their Feishu
 *       conversation in the browser, with the rest of the group still watching in Feishu, is a run
 *       rendered on one surface whose answer has to arrive on another.
 * </ul>
 *
 * <p><b>What may be sent.</b> The first case sends text the deployment wrote about its own
 * workings. The second necessarily carries what a model produced and what a person typed, so the
 * contract cannot be "our own text only" — but foreign text in a chat's own markdown dialect is how
 * a bot ends up notifying a whole group on a stranger's say-so, which is what {@link
 * #quoted(String)} exists to prevent. Anything not written by this deployment goes through it
 * first. What still never travels this way is a payload: an {@code Observation}'s evidence is
 * written by whoever caused the event, and routing decided by something inside it is the one shape
 * {@link Route} is a separate type to make impossible.
 *
 * <p>There is no formatting contract beyond markdown, and an implementation may render it however
 * its surface renders anything else.
 */
public interface Notifier {

  /**
   * Sends {@code text} to {@code route}, doing nothing where the route names nowhere.
   *
   * <p>May throw — a surface that cannot reach its own service has no way to say so otherwise — so
   * a caller reporting a failure has to be ready for this to fail too, and must not let that
   * failure displace the one it was reporting.
   */
  void send(Route route, String text);

  /**
   * The same, threaded onto a message already in that chat where the surface can do it.
   *
   * <p>A mirrored answer belongs *under* the message it answers, not loose in the chat: the group
   * watching that thread is the reason for sending it at all, and a card arriving at the bottom of
   * a busy channel with no thread is a card nobody connects to the question. Where a surface has no
   * notion of replying, or {@code inReplyTo} names nothing it recognises, it says the same thing
   * unthreaded — which is what the default does by ignoring the argument entirely.
   *
   * <p>{@code inReplyTo} is opaque here, exactly as {@link Route#chatId()} is. Core does not know
   * what a message identifier looks like on any surface, so it does not check one: the caller
   * passes the best candidate it has and the implementation decides whether it is a thing it can
   * reply to. That is deliberate — a caller able to tell a Feishu message id from a Slack timestamp
   * would be a caller that has to be changed for every new surface.
   *
   * @param inReplyTo a message in {@code route} to thread under, or null/blank for none
   */
  default void send(final Route route, final String inReplyTo, final String text) {
    send(route, text);
  }

  /**
   * Which chat platform this delivers to, lowercase and stable: {@code feishu}, {@code slack}. For
   * a surface that has to name or draw it — the browser puts the platform's own icon on the button
   * that mirrors an answer there, and a button drawn for the wrong platform is worse than none.
   *
   * <p>Blank by default, which reads as "unnamed" rather than as a platform: a caller that cannot
   * tell which surface it is talking to should offer nothing platform-specific.
   */
  default String surface() {
    return "";
  }

  /**
   * Text somebody else wrote, made safe to embed in what this surface renders.
   *
   * <p>The counterpart of the {@code replyFormat} prompt variable, for the same dialect read rather
   * than written: where that tells the model which tags this surface honours, this neutralises the
   * ones it must not honour from a stranger. Feishu's {@code <at id=all></at>} notifies an entire
   * group and Slack's {@code <!channel>} does the same, so a message quoted verbatim from elsewhere
   * is an instruction to the chat client unless something escapes it first.
   *
   * <p>Returns the text unchanged by default. That is the honest default for an implementation that
   * has not thought about its dialect — it renders wrongly rather than pretending to be safe — so
   * an implementation carrying foreign text <em>must</em> override it.
   */
  default String quoted(String text) {
    return text;
  }
}
