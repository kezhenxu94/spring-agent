package me.kezhenxu94.springagent.core.usermodels;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;

/**
 * The endpoints users have registered for their own chats, and which of them each user is on.
 *
 * <p>The one place that seals and opens an API token, so that no caller above it ever holds the
 * ciphertext or has to remember to encrypt. {@link #tokenOf} is the only method that hands back
 * plaintext, and its only caller is the code building a client — the same split as {@code
 * ShellCredentialStore#resolve}.
 *
 * <p>Only created when {@code app.ai.user-models.encryption-key} is set; see {@code
 * UserModelsConfiguration}.
 */
@Slf4j
@RequiredArgsConstructor
public class UserModelRegistry {

  /**
   * Marks a row that is not an endpoint the user registered but a model of the application's own
   * that they chose off the list.
   *
   * <p>Such a row carries no base URL and no token — {@code UserChatClients} reads a blank base URL
   * as "the application's endpoint" and overrides only the model name, so the application's
   * credentials never reach the database even encrypted.
   *
   * <p>A prefix rather than a flag column so that it also settles the naming: {@code @} is barred
   * from the names users may type (see {@link #validName}), so a chosen built-in model can never
   * collide with an endpoint somebody registered under the same name.
   */
  public static final String BUILTIN_PREFIX = "@";

  /**
   * The row standing for the application's own model, whatever that is configured to be.
   *
   * <p>{@link #BUILTIN_PREFIX} with no model after it, and the difference from a row naming a model
   * is the whole point: a user who asks the application's model to think harder has not asked to be
   * pinned to the model it happens to be pointed at today, so this row carries an effort and no
   * model at all. {@code UserChatClients} reads a blank model the way it reads a blank base URL —
   * as the application's.
   *
   * <p>It is also why {@link #useDefault} activates this row rather than leaving none: without it,
   * choosing the built-in model would silently throw away the effort chosen for the built-in model.
   */
  public static final String DEFAULT_ROW = BUILTIN_PREFIX;

  private final UserModelConfigRepo repo;
  private final AesGcmSealer sealer;
  private final int maxPerUser;

  /**
   * Every endpoint the user has registered, in a stable order so a listing does not shuffle.
   *
   * <p>Built-in choices are not among them: a row recording that somebody picked one of the
   * application's own models is not something they registered, and listing it would offer them the
   * same model twice — once from the application's own list and once as theirs.
   */
  public List<UserModelConfig> list(final String userId) {
    return repo.findByOwnerId(userId).stream()
        .filter(config -> !isBuiltin(config))
        .sorted(Comparator.comparing(UserModelConfig::name))
        .toList();
  }

  /** Whether {@code config} names a model of the application's own rather than an endpoint. */
  public static boolean isBuiltin(final UserModelConfig config) {
    return config.name() != null && config.name().startsWith(BUILTIN_PREFIX);
  }

  /**
   * Whether {@code name} is one a user may give an endpoint.
   *
   * <p>Bars {@link #BUILTIN_PREFIX} so that the two kinds of row cannot collide, and bars
   * whitespace so that a name stays something typeable after {@code /config}.
   */
  public static boolean validName(final String name) {
    return name != null && !name.isBlank() && name.matches("[A-Za-z0-9_.:-]{1,40}");
  }

  public Optional<UserModelConfig> find(final String userId, final String name) {
    return repo.findByOwnerIdAndName(userId, name);
  }

  /**
   * The endpoint this user's runs go to, or empty for the application's own model.
   *
   * <p>One read, and tolerant of finding more than one activated row: a switch is two writes with
   * no transaction around them, so however carefully they are ordered this must not be the thing
   * that fails. The most recently written wins, which is the one the user last asked for.
   */
  public Optional<UserModelConfig> active(final String userId) {
    return repo.findByOwnerId(userId).stream()
        .filter(UserModelConfig::activated)
        .max(
            Comparator.comparing(
                UserModelConfig::updatedAt, Comparator.nullsFirst(Instant::compareTo)));
  }

  /**
   * The plaintext API token of {@code config}. The only method that yields one.
   *
   * <p>Null for a built-in choice, which has none: it borrows the application's own credentials
   * rather than storing a copy of them.
   */
  public String tokenOf(final UserModelConfig config) {
    if (isBuiltin(config) || config.apiKeyCipher() == null) {
      return null;
    }
    return sealer.open("the API token of model " + config.name(), config.apiKeyCipher());
  }

  /**
   * Puts the user on one of the application's own models, named as its endpoint reports it.
   *
   * <p>Recorded as a row so that the choice survives a restart, but a row with nothing secret in
   * it: no base URL, so the client is built on the application's endpoint, and no token, so the
   * application's key is not copied per user into a table.
   *
   * <p>Keeps the reasoning effort already recorded against that model. Switching onto something is
   * not a statement about how hard it should think, and a user who set an effort on a built-in
   * model and then switched away and back would otherwise find it silently gone.
   */
  public void activateBuiltin(final String userId, final String model) {
    final var name = BUILTIN_PREFIX + model;
    final var existing = repo.findByOwnerIdAndName(userId, name);
    deactivateAll(userId, name);
    repo.save(
        UserModelConfig.builder()
            .id(UserModelConfig.idFor(userId, name))
            .ownerId(userId)
            .name(name)
            .model(model)
            .reasoningEffort(existing.map(UserModelConfig::reasoningEffort).orElse(null))
            .activated(true)
            .updatedAt(Instant.now())
            .build());
  }

  /**
   * How a row should be spoken about: the model itself for one of the application's own, the name
   * the user gave it for one of theirs. The {@code @} prefix is bookkeeping and means nothing to
   * whoever chose the model.
   *
   * @return null for {@link #DEFAULT_ROW}, which has no name of its own — every surface has its own
   *     word for the application's model, and a localized one, which core has no business choosing
   */
  public static String displayName(final UserModelConfig config) {
    if (!isBuiltin(config)) {
      return config.name();
    }
    final var model = config.name().substring(BUILTIN_PREFIX.length());
    return model.isEmpty() ? null : model;
  }

  /**
   * Whether the user is already at the ceiling, counting only names they do not already have.
   *
   * <p>Counts endpoints only. The ceiling is there to bound how many of somebody else's credentials
   * one user can have the application hold; a row recording which of the application's own models
   * they chose holds none, and letting those fill the allowance would mean picking models off a
   * menu could stop you registering an endpoint.
   */
  public boolean full(final String userId, final String name) {
    final var existing =
        repo.findByOwnerId(userId).stream().filter(config -> !isBuiltin(config)).toList();
    return existing.size() >= maxPerUser
        && existing.stream().noneMatch(config -> config.name().equals(name));
  }

  public int maxPerUser() {
    return maxPerUser;
  }

  /**
   * Stores an endpoint, sealing its token, and leaves it deactivated. Re-registering a name
   * replaces it, keeping whether it was the one in use — editing the token of the model you are
   * talking through should not silently move you off it.
   *
   * @param reasoningEffort as {@link ReasoningEfforts} spells it, or null to leave the
   *     application's own setting in place
   */
  public UserModelConfig save(
      final String userId,
      final String name,
      final String baseUrl,
      final String model,
      final String token,
      final String reasoningEffort) {
    final var wasActive = repo.findByOwnerIdAndName(userId, name).map(UserModelConfig::activated);
    return repo.save(
        UserModelConfig.builder()
            .id(UserModelConfig.idFor(userId, name))
            .ownerId(userId)
            .name(name)
            .baseUrl(baseUrl)
            .model(model)
            .apiKeyCipher(sealer.seal(token))
            .reasoningEffort(ReasoningEfforts.normalize(reasoningEffort))
            .activated(wasActive.orElse(false))
            .updatedAt(Instant.now())
            .build());
  }

  /**
   * Changes how hard one endpoint should think, and nothing else about it.
   *
   * <p>A write of its own rather than a re-registration, because the token is sealed and never
   * shown again: turning reasoning up would otherwise mean finding a credential the user was told
   * they cannot read back. Nothing is probed either — the endpoint is the one that was already
   * tested, and an effort it rejects is a wrong answer from a model rather than a lockout, since
   * the row it is set on is not necessarily the one in use.
   *
   * @param reasoningEffort one of {@link ReasoningEfforts#CHOICES}, or null to go back to the
   *     application's own setting
   * @return whether the user has an endpoint by that name
   */
  public boolean setEffort(final String userId, final String name, final String reasoningEffort) {
    final var target = repo.findByOwnerIdAndName(userId, name);
    if (target.isEmpty()) {
      return false;
    }
    repo.save(
        target.get().toBuilder()
            .reasoningEffort(ReasoningEfforts.normalize(reasoningEffort))
            .updatedAt(Instant.now())
            .build());
    return true;
  }

  /**
   * Changes how hard the model the user is actually on should think, whatever kind of model that
   * is, and hands back the row it was applied to.
   *
   * <p>The one place that knows what to do when they are on nothing at all — the application's own
   * model, recorded as no row: {@link #DEFAULT_ROW} is written, which is what makes "the built-in
   * model, thinking harder" something a user can ask for without pinning them to the model the
   * deployment happens to be pointed at today.
   *
   * @param reasoningEffort one of {@link ReasoningEfforts#CHOICES}, or null to go back to the
   *     application's own setting
   */
  public UserModelConfig setActiveEffort(final String userId, final String reasoningEffort) {
    final var active = active(userId);
    if (active.isPresent()) {
      setEffort(userId, active.get().name(), reasoningEffort);
      return repo.findByOwnerIdAndName(userId, active.get().name()).orElseThrow();
    }
    // On the application's model, so the effort goes on the row that means exactly that, created
    // here on first use. See DEFAULT_ROW for why it names no model.
    deactivateAll(userId, DEFAULT_ROW);
    return repo.save(
        UserModelConfig.builder()
            .id(UserModelConfig.idFor(userId, DEFAULT_ROW))
            .ownerId(userId)
            .name(DEFAULT_ROW)
            .reasoningEffort(ReasoningEfforts.normalize(reasoningEffort))
            .activated(true)
            .updatedAt(Instant.now())
            .build());
  }

  /**
   * Puts the user on {@code name}, or reports that they have no such endpoint.
   *
   * <p>The write order is load-bearing and the reason this is not two lines at the call site. Every
   * other row is cleared first and only then is this one set, so a failure between the two leaves
   * no activated row rather than two — and no activated row is the application's own model, which
   * is a state the user can talk their way out of. Two would be a coin toss.
   */
  public boolean activate(final String userId, final String name) {
    final var target = repo.findByOwnerIdAndName(userId, name);
    if (target.isEmpty()) {
      return false;
    }
    deactivateAll(userId, name);
    repo.save(target.get().toBuilder().activated(true).updatedAt(Instant.now()).build());
    return true;
  }

  /**
   * Puts the user back on the application's own model, keeping how hard they asked it to think.
   *
   * <p>Always safe, even with nothing stored: with no {@link #DEFAULT_ROW} to activate this leaves
   * no activated row at all, which reads as the application's model with the application's own
   * settings — the state the whole feature falls back to.
   */
  public void useDefault(final String userId) {
    deactivateAll(userId, DEFAULT_ROW);
    repo.findByOwnerIdAndName(userId, DEFAULT_ROW)
        .filter(row -> !row.activated())
        .ifPresent(
            row -> repo.save(row.toBuilder().activated(true).updatedAt(Instant.now()).build()));
  }

  /** Removes an endpoint. Returns whether there was one to remove. */
  public boolean delete(final String userId, final String name) {
    if (repo.findByOwnerIdAndName(userId, name).isEmpty()) {
      return false;
    }
    // Nothing else to clean up: the row carried its own activation, so deleting the one in use
    // leaves the user on the application's model by construction.
    repo.deleteByOwnerIdAndName(userId, name);
    return true;
  }

  private void deactivateAll(final String userId, final String except) {
    for (final var config : repo.findByOwnerId(userId)) {
      if (config.activated() && !config.name().equals(except)) {
        repo.save(config.toBuilder().activated(false).updatedAt(Instant.now()).build());
      }
    }
  }
}
