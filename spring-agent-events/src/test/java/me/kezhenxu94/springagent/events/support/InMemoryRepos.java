package me.kezhenxu94.springagent.events.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.kezhenxu94.springagent.core.dao.models.ObservedEvent;
import me.kezhenxu94.springagent.core.dao.models.Situation;
import me.kezhenxu94.springagent.core.dao.repo.ObservedEventRepo;
import me.kezhenxu94.springagent.core.dao.repo.ProcessedMessageRepo;
import me.kezhenxu94.springagent.core.dao.repo.SituationRepo;

/**
 * The three repositories this module uses, in a map.
 *
 * <p>Fakes rather than mocks, because what is under test is arithmetic over stored state and a mock
 * would only assert that the arithmetic asked the questions it was written to ask. These behave the
 * way the real backends do on the points that matter: {@code save} is an upsert keyed by id, {@code
 * claim} is first-caller-wins and never expires, and the finders return unordered lists — so a test
 * that only passes because rows came back in insertion order fails here too.
 *
 * <p>The behaviour of the real three against the real contract is pinned separately, and against
 * all of them at once, in {@code AbstractPersistenceBackendTest}.
 */
public final class InMemoryRepos {

  public final Situations situations = new Situations();
  public final Events events = new Events();
  public final Claims claims = new Claims();

  public static final class Situations implements SituationRepo {
    private final Map<String, Situation> stored = new ConcurrentHashMap<>();

    @Override
    public Situation save(final Situation situation) {
      // Copied on the way in and out, as a round trip through a database is: a test that mutated
      // the
      // object it saved would otherwise see the change without saving it, and pass for the wrong
      // reason.
      stored.put(situation.id(), situation.toBuilder().build());
      return situation.toBuilder().build();
    }

    @Override
    public Optional<Situation> findById(final String id) {
      return Optional.ofNullable(stored.get(id)).map(s -> s.toBuilder().build());
    }

    @Override
    public List<Situation> findByCorrelationKeyAndStatus(
        final String correlationKey, final Situation.Status status) {
      return stored.values().stream()
          .filter(s -> correlationKey.equals(s.correlationKey()) && s.status() == status)
          .map(s -> s.toBuilder().build())
          .toList();
    }

    @Override
    public List<Situation> findByStatus(final Situation.Status status) {
      return stored.values().stream()
          .filter(s -> s.status() == status)
          .map(s -> s.toBuilder().build())
          .toList();
    }

    @Override
    public List<Situation> findByPhase(final Situation.Phase phase) {
      return stored.values().stream()
          .filter(s -> s.phase() == phase)
          .map(s -> s.toBuilder().build())
          .toList();
    }

    public List<Situation> all() {
      return List.copyOf(stored.values());
    }

    public Situation only() {
      if (stored.size() != 1) {
        throw new AssertionError("expected exactly one situation, found " + stored.size());
      }
      return stored.values().iterator().next();
    }
  }

  public static final class Events implements ObservedEventRepo {
    private final Map<String, ObservedEvent> stored = new ConcurrentHashMap<>();

    @Override
    public ObservedEvent save(final ObservedEvent event) {
      stored.put(event.id(), event);
      return event;
    }

    @Override
    public List<ObservedEvent> findBySituationId(final String situationId) {
      // Deliberately in no useful order, which is what the contract promises and what the Redis
      // backend actually does.
      final var found =
          new ArrayList<>(
              stored.values().stream().filter(e -> situationId.equals(e.situationId())).toList());
      java.util.Collections.reverse(found);
      return found;
    }

    public int size() {
      return stored.size();
    }
  }

  /** {@code claim} as every backend implements it: atomic, first caller wins, never expires. */
  public static final class Claims implements ProcessedMessageRepo {
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean claim(final String id) {
      return claimed.add(id);
    }

    @Override
    public void release(final String id) {
      claimed.remove(id);
    }

    public boolean isClaimed(final String id) {
      return claimed.contains(id);
    }

    public int size() {
      return claimed.size();
    }
  }
}
