package me.kezhenxu94.springagent.core.notify;

import me.kezhenxu94.springagent.core.observing.Route;

/**
 * Says something to a {@link Route} without a run behind it — the one way anything here reaches a
 * person other than by the agent choosing to.
 *
 * <p>Core ships no implementation, and a deployment without one simply has nowhere to send these,
 * which is a supported configuration rather than a broken one. A surface implements it as a
 * {@code @Bean}; whoever wants to send something takes an {@code ObjectProvider<Notifier>} and does
 * nothing when there is none.
 *
 * <p><b>Why this exists at all, when a run can already talk.</b> Everything the agent says reaches
 * somebody because a surface's {@code AgentResponseListener} renders the run it belongs to. That
 * covers every case but one: a run that failed, or never started. A background run is not rendered
 * anywhere, so the failure of an unattended run — event triage, a scheduled task — is visible only
 * in a log nobody is reading at the time. Reporting it needs something that is not a run, and that
 * is this. It follows that an implementation must not need a model, an agent or a live request to
 * do its work: the failure most worth reporting is the one where the model is what broke.
 *
 * <p>What is sent is text the deployment wrote about its own workings, never anything a model
 * produced and never anything from a payload. There is no formatting contract beyond markdown, and
 * an implementation may render it however its surface renders anything else.
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
}
