package me.kezhenxu94.springagent.core.usermodels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.core.dao.models.UserModelConfig;
import me.kezhenxu94.springagent.core.dao.repo.UserModelConfigRepo;
import me.kezhenxu94.springagent.core.security.AesGcmSealer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The command that is the way out of a model that does not answer, so what matters here is that it
 * never needs one to work.
 */
class UserModelCommandTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private final InMemoryRepo repo = new InMemoryRepo();
  private final UserModelRegistry registry =
      new UserModelRegistry(repo, new AesGcmSealer(KEY, "test"), 10);
  private final UserModelCommand command = new UserModelCommand(registry, messages());

  private static CoreMessages messages() {
    final var source = new ResourceBundleMessageSource();
    source.setBasename("core.messages");
    source.setDefaultEncoding("UTF-8");
    source.setFallbackToSystemLocale(false);
    return new CoreMessages(
        source, new SpringAgentProperties(null, null, Locale.ENGLISH, null, null));
  }

  @Test
  @DisplayName("with nothing registered it says so rather than failing")
  void empty() {
    assertThat(command.handle("u1", "")).contains("no models of your own");
  }

  @Test
  @DisplayName("naming a model switches to it")
  void switches() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);

    assertThat(command.handle("u1", "kimi")).contains("kimi");
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
  }

  @Test
  @DisplayName("default goes back to the built-in model")
  void toDefault() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.activate("u1", "kimi");

    assertThat(command.handle("u1", "default")).isNotBlank();
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("a name nobody registered changes nothing and lists what there is")
  void unknown() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.activate("u1", "kimi");

    final var reply = command.handle("u1", "typo");

    assertThat(reply).contains("typo").contains("kimi");
    assertThat(registry.active("u1")).map(UserModelConfig::name).contains("kimi");
  }

  @Test
  @DisplayName("the argument is trimmed and case-insensitive for default")
  void tolerantParsing() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    registry.activate("u1", "kimi");

    command.handle("u1", "  DEFAULT  ");

    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("a name and an effort set how hard that model thinks, without switching onto it")
  void setsEffort() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);

    assertThat(command.handle("u1", "kimi high")).contains("kimi").contains("high");

    assertThat(registry.find("u1", "kimi")).map(UserModelConfig::reasoningEffort).contains("high");
    assertThat(registry.active("u1")).isEmpty();
  }

  @Test
  @DisplayName("an effort that is not one of the values changes nothing and says what they are")
  void rejectsUnknownEffort() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", "low");

    final var reply = command.handle("u1", "kimi highest");

    assertThat(reply).contains("highest").contains("xhigh");
    assertThat(registry.find("u1", "kimi")).map(UserModelConfig::reasoningEffort).contains("low");
  }

  @Test
  @DisplayName("an effort for a model nobody registered lists what there is")
  void effortOnUnknownModel() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);

    assertThat(command.handle("u1", "typo high")).contains("typo").contains("kimi");
  }

  @Test
  @DisplayName("the parameter can be turned off, which is not the same as leaving it alone")
  void notSent() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", "high");

    command.handle("u1", "kimi not-sent");

    assertThat(registry.find("u1", "kimi"))
        .map(UserModelConfig::reasoningEffort)
        .contains(ReasoningEfforts.NOT_SENT);
  }

  @Test
  @DisplayName("default and an effort asks the built-in model to think that hard")
  void builtinEffort() {
    assertThat(command.handle("u1", "default high")).contains("high");

    final var row = registry.active("u1").orElseThrow();
    // No model named, so the user follows the deployment's own however that is reconfigured; see
    // UserModelRegistry.DEFAULT_ROW.
    assertThat(row.model()).isNull();
    assertThat(row.baseUrl()).isNull();
    assertThat(row.reasoningEffort()).isEqualTo("high");
  }

  @Test
  @DisplayName("the built-in model keeps the effort set on it across a switch away and back")
  void builtinEffortSurvivesSwitching() {
    registry.save("u1", "kimi", "https://kimi/v1", "kimi-k2", "t", null);
    command.handle("u1", "default high");

    command.handle("u1", "kimi");
    command.handle("u1", "default");

    assertThat(registry.active("u1")).map(UserModelConfig::reasoningEffort).contains("high");
  }

  @Test
  @DisplayName("what is in use is named as the user knows it, effort and all")
  void statusNamesTheBuiltin() {
    command.handle("u1", "default max");

    assertThat(command.handle("u1", ""))
        .contains("the default model")
        .contains("max")
        .doesNotContain("@");
  }

  /** Enough of the contract to exercise the command. */
  private static final class InMemoryRepo implements UserModelConfigRepo {
    private final Map<String, UserModelConfig> rows = new LinkedHashMap<>();

    @Override
    public UserModelConfig save(final UserModelConfig config) {
      rows.put(config.id(), config);
      return config;
    }

    @Override
    public List<UserModelConfig> findByOwnerId(final String ownerId) {
      return rows.values().stream().filter(row -> row.ownerId().equals(ownerId)).toList();
    }

    @Override
    public Optional<UserModelConfig> findByOwnerIdAndName(final String ownerId, final String name) {
      return Optional.ofNullable(rows.get(UserModelConfig.idFor(ownerId, name)));
    }

    @Override
    public void deleteByOwnerIdAndName(final String ownerId, final String name) {
      rows.remove(UserModelConfig.idFor(ownerId, name));
    }
  }
}
