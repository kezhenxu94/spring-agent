package me.kezhenxu94.springagent.events.situation;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.identity.SystemIdentity;
import me.kezhenxu94.springagent.core.identity.SystemIdentityProvider;
import me.kezhenxu94.springagent.events.config.EventsProperties;
import org.springframework.stereotype.Component;

/**
 * The identities triage runs act as, so that somebody can find what they have remembered.
 *
 * <p>An {@code owner.user-id} is an account nobody signs in to and no directory lists, and yet it
 * accumulates a knowledge base — {@code WritePlaybook} writes into exactly this one. An
 * administrator looking for that has otherwise to read the deployment's configuration to learn the
 * id to type, which is the gap this closes.
 *
 * <p>Read from {@link EventsProperties#policyFor(String)} on every call rather than compiled once,
 * because it is asked at most once per page load and resolving the layers is what makes the answer
 * the same one {@link SituationSweeper} runs under. Disabled sources resolve to nothing and so are
 * absent by construction, and so is the whole list when {@code app.events.enabled} is false: an
 * identity nothing runs as is not one worth offering.
 */
@Component
@RequiredArgsConstructor
public class TriageIdentities implements SystemIdentityProvider {

  private final EventsProperties properties;

  @Override
  public List<SystemIdentity> identities() {
    // By id rather than by source: two sources sharing an owner share its knowledge base, so they
    // are one thing to read and one row to draw. Sorted, so the order is the deployment's spelling
    // rather than whatever the properties map iterated in.
    final var bySource = new TreeMap<String, List<String>>();
    for (final var source : properties.sources().keySet()) {
      properties
          .policyFor(source)
          .map(policy -> policy.owner().userId())
          .filter(userId -> !Strings.isNullOrEmpty(userId))
          .ifPresent(
              userId -> bySource.computeIfAbsent(userId, id -> new ArrayList<>()).add(source));
    }
    return bySource.entrySet().stream()
        .map(
            entry ->
                new SystemIdentity(entry.getKey(), entry.getValue().stream().sorted().toList()))
        .toList();
  }
}
