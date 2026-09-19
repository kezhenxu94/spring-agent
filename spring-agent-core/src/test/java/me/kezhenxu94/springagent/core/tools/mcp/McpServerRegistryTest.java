package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import me.kezhenxu94.springagent.core.dao.repo.McpServerConfigRepo;
import me.kezhenxu94.springagent.core.tools.mcp.McpRegistryException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The parts of the registry that only it decides.
 *
 * <p>Registration itself — the URL check, the prefix collision, the probe — is covered through the
 * tools in {@code McpServerManagementToolsPrefixTest} and {@code
 * McpServerManagementToolsShareTest}, which is where it was tested before this class existed and
 * where the sentences it produces are checked too. What is here is what the extraction added: a
 * name this application configures being refused at registration and not only afterwards, and the
 * enabled flag becoming settable at all.
 */
class McpServerRegistryTest {

  private static final String OWNER_ID = "ou_owner";

  private final McpServerConfigRepo repo = mock(McpServerConfigRepo.class);
  private final McpClientFactory clientFactory = mock(McpClientFactory.class);
  private McpServerRegistry registry;

  @BeforeEach
  void setUp() {
    registry = registryWith(configuredCompany());
    when(repo.findByOwnerIdAndName(any(), any())).thenReturn(Optional.empty());
    when(repo.findAccessibleTo(any(), any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("a name this application configures cannot be registered over")
  void refusesToShadowAConfiguredName() {
    // Without this, registering "company" stores a row whose tools assemble alongside the
    // application's own of the same name — and AgentToolsProvider refuses the whole run over the
    // duplicate, costing every MCP tool rather than the one. The row could then not be removed
    // either: every mutation refuses a configured name, so it was reachable only by the tools'
    // "not yours to manage". Refusing at the door is the only place this can be said usefully.
    assertThatThrownBy(
            () ->
                registry.register(
                    OWNER_ID,
                    null,
                    new McpServerSpec(
                        "company", "https://mine.example/mcp", null, null, null, null, null, null),
                    Map.of()))
        .isInstanceOf(McpRegistryException.class)
        .extracting(e -> ((McpRegistryException) e).reason())
        .isEqualTo(Reason.APPLICATION_CONFIGURED);
    verify(repo, never()).save(any());
  }

  @Test
  @DisplayName("what this application configures is reported with its endpoint path settled")
  void reportsConfiguredWithTheEndpoint() {
    assertThat(registry.applicationConfigured())
        .singleElement()
        .isEqualTo(new McpConfiguredServer("company", "https://mcp.corp/mcp"));
  }

  @Test
  @DisplayName("an application that configures none, or turns MCP off, still has a registry")
  void survivesWithoutTheProperties() {
    assertThat(registryWith(null).applicationConfigured()).isEmpty();
    assertThat(registryWith(null).isApplicationConfigured("anything")).isFalse();
  }

  @Test
  @DisplayName("a server is muted without being forgotten")
  void setsEnabled() {
    final var stored =
        McpServerConfig.builder()
            .id("1")
            .ownerId(OWNER_ID)
            .name("github")
            .url("https://x.example/mcp")
            .enabled(true)
            .build();
    when(repo.findByOwnerIdAndName(OWNER_ID, "github")).thenReturn(Optional.of(stored));

    final var muted = registry.setEnabled(OWNER_ID, "github", false);

    assertThat(muted.enabled()).isFalse();
    // Still stored, URL and all: the alternative to muting is removing, which throws away a URL, a
    // prefix the model has learnt and a credential somebody has to go and find again.
    assertThat(muted.url()).isEqualTo("https://x.example/mcp");
    verify(repo).save(stored);
  }

  @Test
  @DisplayName("muting one the caller does not own is refused rather than silently doing nothing")
  void refusesToMuteSomebodyElses() {
    assertThatThrownBy(() -> registry.setEnabled(OWNER_ID, "theirs", false))
        .isInstanceOf(McpRegistryException.class)
        .extracting(e -> ((McpRegistryException) e).reason())
        .isEqualTo(Reason.UNKNOWN);
  }

  @Test
  @DisplayName("muting one this application configures says so, rather than that it is missing")
  void refusesToMuteAConfiguredOne() {
    assertThatThrownBy(() -> registry.setEnabled(OWNER_ID, "company", false))
        .isInstanceOf(McpRegistryException.class)
        .extracting(e -> ((McpRegistryException) e).reason())
        .isEqualTo(Reason.APPLICATION_CONFIGURED);
  }

  @SuppressWarnings("unchecked")
  private McpServerRegistry registryWith(final McpStreamableHttpClientProperties configured) {
    final ObjectProvider<McpStreamableHttpClientProperties> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(configured);
    return new McpServerRegistry(repo, clientFactory, provider);
  }

  private static McpStreamableHttpClientProperties configuredCompany() {
    final var properties = new McpStreamableHttpClientProperties();
    // No endpoint, so the registry supplies Spring AI's own default of /mcp — which is the half
    // of the URL a caller reassembling this by hand would leave out.
    properties
        .getConnections()
        .put(
            "company",
            new McpStreamableHttpClientProperties.ConnectionParameters("https://mcp.corp", null));
    return properties;
  }
}
