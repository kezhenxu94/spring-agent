package me.kezhenxu94.springagent.tools.shell.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.EnvFromSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.kezhenxu94.springagent.core.config.Admins;
import me.kezhenxu94.springagent.core.config.SpringAgentProperties;
import me.kezhenxu94.springagent.tools.shell.kubernetes.KubernetesShellProperties.Credentials.SharedSecretSelector;
import org.junit.jupiter.api.Test;

class UserPodManagerTest {

  private static final String USER = "ou_user";
  private static final String GROUP = "oc_group";
  private static final String TENANT = "tenant_a";

  @Test
  void resolvesPlaceholdersAgainstTheScope() {
    final var selector =
        new SharedSecretSelector(
            Map.of("springagent.io/group-id", "{groupId}", "springagent.io/shared", "true"));

    assertThat(selector.resolve(USER, GROUP, TENANT))
        .contains(Map.of("springagent.io/group-id", GROUP, "springagent.io/shared", "true"));
  }

  @Test
  void doesNotApplyWhenAPlaceholderHasNothingToFillIt() {
    final var selector = new SharedSecretSelector(Map.of("springagent.io/group-id", "{groupId}"));

    // The point of the whole design: a blank group must not become a selector matching every
    // Secret that stores a blank group, which is every other group's.
    assertThat(selector.resolve(USER, null, TENANT)).isEmpty();
    assertThat(selector.resolve(USER, "  ", TENANT)).isEmpty();
  }

  @Test
  void doesNotApplyWhenTheIdIsNotALegalLabelValue() {
    final var selector = new SharedSecretSelector(Map.of("springagent.io/group-id", "{groupId}"));

    assertThat(selector.resolve(USER, "has spaces", TENANT)).isEmpty();
    assertThat(selector.resolve(USER, "-leading-dash", TENANT)).isEmpty();
    assertThat(selector.resolve(USER, "a".repeat(64), TENANT)).isEmpty();
    assertThat(selector.resolve(USER, "a".repeat(63), TENANT)).isPresent();
  }

  @Test
  void appliesWithNoPlaceholderAtAll() {
    final var selector = new SharedSecretSelector(Map.of("springagent.io/shared", "all"));

    assertThat(selector.resolve(USER, null, null)).contains(Map.of("springagent.io/shared", "all"));
  }

  @Test
  void refusesASelectorThatWouldMatchEverySecret() {
    assertThatThrownBy(() -> new SharedSecretSelector(Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("matches every Secret");
    assertThatThrownBy(() -> new SharedSecretSelector(Map.of("springagent.io/shared", " ")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theUsersOwnSecretIsTheLastEnvFromSource() {
    final var manager = manager();
    final var sharedNames = List.of("shared-everyone", "shared-tenant-a", "shared-group");

    final var envFrom = envFromNames(manager, sharedNames);

    // envFrom lets a later source's key win, so this order is the precedence rule: pre-provisioned
    // Secrets weakest, the user's own Secret last and therefore winning every conflict.
    assertThat(envFrom)
        .containsExactly(
            "shared-everyone",
            "shared-tenant-a",
            "shared-group",
            manager.credentialsSecretName("tenant/" + TENANT),
            manager.credentialsSecretName("groups/" + GROUP),
            manager.credentialsSecretName(USER));
  }

  @Test
  void everySharedSourceIsOptional() {
    final var manager = manager();

    final var job =
        manager.buildJob(
            "job", USER, GROUP, TENANT, "scope", List.of("shared-everyone", "shared-group"));

    assertThat(job.getSpec().getTemplate().getSpec().getContainers().getFirst().getEnvFrom())
        .extracting(source -> source.getSecretRef().getOptional())
        .containsOnly(true);
  }

  @Test
  void withoutSharedSecretsNothingChanges() {
    final var manager = manager();

    assertThat(envFromNames(manager, List.of()))
        .containsExactly(
            manager.credentialsSecretName("tenant/" + TENANT),
            manager.credentialsSecretName("groups/" + GROUP),
            manager.credentialsSecretName(USER));
  }

  private static List<String> envFromNames(
      final UserPodManager manager, final List<String> sharedNames) {
    final var job = manager.buildJob("job", USER, GROUP, TENANT, "scope", sharedNames);
    return job.getSpec().getTemplate().getSpec().getContainers().getFirst().getEnvFrom().stream()
        .map(EnvFromSource::getSecretRef)
        .map(ref -> ref.getName())
        .toList();
  }

  private static UserPodManager manager() {
    final var properties =
        new KubernetesShellProperties(
            "sandbox",
            "shell:latest",
            "/data",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new KubernetesShellProperties.Storage(
                List.of(new KubernetesShellProperties.Storage.Mount("pvc", "/data", null))),
            null,
            null);
    return new UserPodManager(null, properties, noAdmins());
  }

  private static Admins noAdmins() {
    return new Admins(
        new SpringAgentProperties(
            null,
            new SpringAgentProperties.Ai(Set.of(), Map.of(), null, null, null, null, null, null),
            Locale.ENGLISH,
            null));
  }
}
