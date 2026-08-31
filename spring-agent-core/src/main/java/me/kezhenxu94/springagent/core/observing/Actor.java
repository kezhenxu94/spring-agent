package me.kezhenxu94.springagent.core.observing;

/**
 * Who caused an {@link Observation}, and whether anybody vouched for it being them.
 *
 * <p>Both halves in one value because they are only meaningful together. A name on its own invites
 * the question this type answers, and the two answers want opposite treatment: one is a fact
 * established about the delivery and may decide whether the agent hears about it at all, the other
 * is a string its author typed and is evidence like the rest of the payload.
 *
 * <p><b>Which is why the name a decision may be made on is behind {@link #authenticatedName()}, and
 * {@link #name()} is not it.</b> An allow-list matched against a name nobody verified is worse than
 * no allow-list, because the attacker writes both sides of the comparison — so the accessor that
 * feeds one is the accessor whose name says what it is, and it is null where nothing vouched.
 * {@code TrustedActors} calls that one and nothing else. A caller reaching for {@link #name()} to
 * decide something is a caller that has to type the word {@code name} where {@code
 * authenticatedName} was available, which is the whole of the protection this shape offers and is
 * why the boolean is not the interface.
 *
 * <p>Never routing, never the identity a run assumes, never rendered into a prompt as a fact.
 * Whoever caused an event is <em>also</em> in {@link Observation#summary()}, and that is not a
 * duplication to tidy away: the summary carries the display name its owner chose, shown to the
 * model as evidence, while this is what the transport was able to establish. The identity a triage
 * run assumes is neither, for the reasons in {@code SituationTriageScenario}.
 *
 * @param name who the event says caused it — verified or merely claimed, but always somebody. Never
 *     null and never blank: an absent identity is an absent {@code Actor}, which is what the
 *     factories return for one.
 * @param authenticated whether the transport established that the event really came from {@link
 *     #name}
 */
public record Actor(String name, boolean authenticated) {

  public Actor {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("An actor with no name is no actor; report null instead");
    }
    name = name.trim();
  }

  /**
   * An identity the transport established, or null where there was no name to establish one from.
   *
   * <p>Authenticated is the whole of the word, and it is a claim the source makes on its own
   * account. GitHub names the actor inside a body an HMAC has already covered, so that is one; an
   * email {@code From:} header is a string anybody can type, so that is not, whatever it says.
   * Calling this with a name lifted from an unauthenticated part of a payload turns every {@code
   * trusted-actors} list in the deployment into a bypass.
   */
  public static Actor authenticated(final String name) {
    return name == null || name.isBlank() ? null : new Actor(name, true);
  }

  /**
   * What the event claims about itself and nothing more, or null where it claims nothing.
   *
   * <p>The honest answer wherever a transport can read a name but not check it, and reporting one
   * costs nothing: no decision is made on a claim, so this admits nobody who would otherwise have
   * been kept out. What it buys is that an intake watching a source for its own reasons — counting
   * what arrives, warning about a stranger — can say who was at the door rather than only that
   * somebody was.
   */
  public static Actor claimed(final String name) {
    return name == null || name.isBlank() ? null : new Actor(name, false);
  }

  /**
   * The name where somebody vouched for it, null otherwise.
   *
   * <p>The one accessor a decision may be made on, and the reason this type exists rather than a
   * pair of fields on {@link Observation}.
   */
  public String authenticatedName() {
    return authenticated ? name : null;
  }

  /** For logs, where the distinction matters as much as the name does. */
  @Override
  public String toString() {
    return authenticated ? name : name + " (unverified)";
  }
}
