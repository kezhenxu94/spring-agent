package me.kezhenxu94.springagent.core.identity;

import java.util.List;

/**
 * Whoever knows about identities of the agent's own, asked so that a surface can offer them.
 *
 * <p>An SPI rather than a lookup in core because core configures none of these: the identities
 * exist wherever unattended runs are configured, which today is {@code spring-agent-events} and
 * tomorrow could be anything with the same shape. A surface that wants to name them — the browser's
 * knowledge base page, which lets an administrator read somebody else's — asks every bean of this
 * type and never depends on the module that answers.
 *
 * <p>Answering is not a permission to read anything: what these identities may be used for is
 * decided where they are used. The knowledge page still checks {@code app.ai.admins} before it
 * draws them, and the endpoint behind it checks again.
 */
@FunctionalInterface
public interface SystemIdentityProvider {

  /** The identities this provider knows of, in whatever order it considers meaningful. */
  List<SystemIdentity> identities();
}
