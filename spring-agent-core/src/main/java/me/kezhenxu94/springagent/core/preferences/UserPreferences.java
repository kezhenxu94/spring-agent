package me.kezhenxu94.springagent.core.preferences;

import com.google.common.base.Strings;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.agent.AgentScenario;
import me.kezhenxu94.springagent.core.agent.BuiltInScenarios;
import me.kezhenxu94.springagent.core.agent.ScenarioMemos;
import me.kezhenxu94.springagent.core.dao.models.UserPreference;
import me.kezhenxu94.springagent.core.dao.repo.UserPreferenceRepo;
import org.springframework.stereotype.Component;

/**
 * What a person has said they want, read the way a run needs it.
 *
 * <p>The row is {@link UserPreference} and the store is a repository like any other; this is the
 * layer that turns a stored word into the thing the runtime acts on, and it exists because two
 * callers need exactly the same reading — the surfaces building a request, and {@code
 * UserPreferenceTools} telling a person what they have set.
 *
 * <p><b>Reading a preference must never cost a run.</b> A preference is a convenience, and a store
 * that is briefly unreachable is not a reason to refuse somebody an answer — so every read here
 * falls back to the default and logs, where a write is allowed to fail and say so. That asymmetry
 * is deliberate: a person who sets something and is told it worked must not find it did not, while
 * a person asking a question does not care which scenario served them until it is the wrong one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPreferences {

  private final UserPreferenceRepo repo;

  /**
   * Which scenario resolves a stored memo, and the check that a stored one is still a word this
   * deployment knows — a scenario can be removed, or a bean that contributed one can stop being
   * registered, and the row outlives both.
   */
  private final ScenarioMemos memos;

  /**
   * The scenario this person's runs start in, or {@link BuiltInScenarios#CHAT} where they have
   * expressed no preference.
   *
   * <p>This is the <i>fallback</i> a surface hands to {@link ScenarioMemos#parse}, never the answer
   * itself: a memo typed into the message names the scenario for that one turn and has to win, or
   * somebody whose default is {@code kb} could never ask an ordinary question again. That is what
   * {@code /full} is for, and it only works because the memo is read after this.
   */
  public AgentScenario scenarioFor(final String userId) {
    return scenarioMemoFor(userId).flatMap(memos::named).orElse(BuiltInScenarios.CHAT);
  }

  /**
   * The word this person stored, whether or not it still resolves — for a tool reporting back what
   * they set, which must say what is in the row rather than what the runtime made of it.
   */
  public Optional<String> scenarioMemoFor(final String userId) {
    return find(userId).map(UserPreference::scenario).filter(memo -> !Strings.isNullOrEmpty(memo));
  }

  /** Everything stored for this person, or nothing where they have set none. */
  public Optional<UserPreference> find(final String userId) {
    if (Strings.isNullOrEmpty(userId)) {
      return Optional.empty();
    }
    try {
      return repo.findById(userId);
    } catch (Exception e) {
      // Never the run's problem: an unreachable store costs a person their preference for this
      // turn, not their answer.
      log.warn("Could not read the preferences of {}, carrying on with the defaults", userId, e);
      return Optional.empty();
    }
  }

  /**
   * Stores {@code memo} as this person's default scenario, or clears it where {@code memo} is null
   * or blank.
   *
   * <p>Refuses a word no scenario answers to, rather than storing it and falling back silently on
   * every run afterwards. The caller is expected to have something better to say than this returns
   * — see {@code UserPreferenceTools}.
   *
   * @return the word actually stored, empty where the preference was cleared
   * @throws IllegalArgumentException where {@code memo} names no scenario
   */
  public Optional<String> setScenario(final String userId, final String memo) {
    final var cleared = Strings.isNullOrEmpty(memo) || memo.isBlank();
    final var word = cleared ? null : memo.strip().toLowerCase(java.util.Locale.ROOT);
    if (!cleared && memos.named(word).isEmpty()) {
      throw new IllegalArgumentException(word);
    }
    final var existing =
        repo.findById(userId).orElseGet(() -> UserPreference.builder().id(userId).build());
    repo.save(existing.toBuilder().id(userId).scenario(word).updatedAt(Instant.now()).build());
    return Optional.ofNullable(word);
  }

  /**
   * Forgets everything this person set, putting every decision back to the deployment's default.
   */
  public void reset(final String userId) {
    repo.deleteById(userId);
  }

  /** The memos a person may choose between, for a tool that has to list them. */
  public java.util.Set<String> selectableScenarios() {
    return memos.names();
  }
}
