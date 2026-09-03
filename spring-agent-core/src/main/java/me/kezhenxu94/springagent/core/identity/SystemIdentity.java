package me.kezhenxu94.springagent.core.identity;

import java.util.List;

/**
 * An identity this deployment runs as itself, rather than one belonging to a person.
 *
 * <p>Such an identity is configured rather than signed in — an event source's {@code
 * owner.user-id}, say — so nothing that lists people will ever mention it, and yet it owns files,
 * memories and a knowledge base like any other. That is what makes it worth reporting: an
 * administrator looking for what a triage run has been remembering has no way to learn the id
 * except by reading the deployment's configuration.
 *
 * @param userId the id the runs act under, exactly as it is stamped on what they store
 * @param sources what the identity is used for, as the names whatever configured it calls them by —
 *     event source names, for the one provider that ships here. Left to the reader to phrase: these
 *     are identifiers, and the sentence around them belongs to whoever is drawing the list.
 */
public record SystemIdentity(String userId, List<String> sources) {}
