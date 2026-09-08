package me.kezhenxu94.springagent.core.tools.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.kezhenxu94.springagent.core.dao.models.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The prefix a server's tools are named after: chosen, or derived from the name. */
class McpToolPrefixTest {

  @Test
  @DisplayName("a chosen prefix is used as it stands")
  void chosenPrefixWins() {
    assertThat(McpClientFactory.toolPrefix(config("github-mcp", "github"))).isEqualTo("github");
  }

  @Test
  @DisplayName("a blank or absent prefix falls back to the hash of the server name")
  void blankPrefixFallsBackToHash() {
    final var expected = McpClientFactory.hashPrefix("github-mcp");
    assertThat(McpClientFactory.toolPrefix(config("github-mcp", null))).isEqualTo(expected);
    assertThat(McpClientFactory.toolPrefix(config("github-mcp", "  "))).isEqualTo(expected);
  }

  @Test
  @DisplayName("a chosen prefix is trimmed rather than carried into every tool name")
  void chosenPrefixIsTrimmed() {
    assertThat(McpClientFactory.toolPrefix(config("github-mcp", " github "))).isEqualTo("github");
  }

  @Test
  @DisplayName("a prefix carrying anything but letters, digits, underscore or hyphen is refused")
  void refusesUnsafeCharacters() {
    for (final var prefix : new String[] {"git hub", "github!", "github.mcp", "github/mcp", "🚀"}) {
      assertThatThrownBy(() -> McpClientFactory.validateToolPrefix(prefix))
          .as("should refuse %s", prefix)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("a prefix long enough to crowd out the tool's own name is refused")
  void refusesOverlyLongPrefix() {
    assertThatCode(() -> McpClientFactory.validateToolPrefix("a".repeat(32)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> McpClientFactory.validateToolPrefix("a".repeat(33)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("an unusable stored prefix throws rather than quietly reverting to the hash")
  void storedUnsafePrefixThrows() {
    assertThatThrownBy(() -> McpClientFactory.toolPrefix(config("github-mcp", "git hub")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static McpServerConfig config(final String name, final String toolPrefix) {
    return McpServerConfig.builder().ownerId("ou_1").name(name).toolPrefix(toolPrefix).build();
  }
}
