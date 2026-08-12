package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeishuWikiToolsTest {

  private final FeishuWikiTools tools = new FeishuWikiTools(null);

  @Test
  @DisplayName("resolveTokenAndObjType passes a bare token through unchanged")
  void bareToken() {
    final var resolved = tools.resolveTokenAndObjType("wikcnKQ1k3p8Vabcef", null);

    assertThat(resolved.token()).isEqualTo("wikcnKQ1k3p8Vabcef");
    assertThat(resolved.objType()).isNull();
  }

  @Test
  @DisplayName("resolveTokenAndObjType extracts the token from a wiki node URL")
  void wikiUrl() {
    final var resolved =
        tools.resolveTokenAndObjType(
            "https://lv3wgjcyixc.feishu.cn/wiki/FYiRwAYuRi1HUZkskvoco4xPnBf", null);

    assertThat(resolved.token()).isEqualTo("FYiRwAYuRi1HUZkskvoco4xPnBf");
    assertThat(resolved.objType()).isEqualTo("wiki");
  }

  @Test
  @DisplayName("resolveTokenAndObjType maps the sheets URL segment to the sheet obj_type")
  void sheetsUrl() {
    final var resolved =
        tools.resolveTokenAndObjType(
            "https://lv3wgjcyixc.feishu.cn/sheets/shtcngNygNfuqhxTBf588jabcef", null);

    assertThat(resolved.token()).isEqualTo("shtcngNygNfuqhxTBf588jabcef");
    assertThat(resolved.objType()).isEqualTo("sheet");
  }

  @Test
  @DisplayName("resolveTokenAndObjType maps the base URL segment to the bitable obj_type")
  void baseUrl() {
    final var resolved =
        tools.resolveTokenAndObjType(
            "https://lv3wgjcyixc.feishu.cn/base/bblcngNygNfuqhxTBf588jabcef", null);

    assertThat(resolved.objType()).isEqualTo("bitable");
  }

  @Test
  @DisplayName("resolveTokenAndObjType strips query strings from the URL")
  void urlWithQuery() {
    final var resolved =
        tools.resolveTokenAndObjType(
            "https://lv3wgjcyixc.feishu.cn/wiki/FYiRwAYuRi1HUZkskvoco4xPnBf?from=xxx", null);

    assertThat(resolved.token()).isEqualTo("FYiRwAYuRi1HUZkskvoco4xPnBf");
  }

  @Test
  @DisplayName(
      "resolveTokenAndObjType prefers an explicitly provided objType over the inferred one")
  void explicitObjTypeWins() {
    final var resolved =
        tools.resolveTokenAndObjType(
            "https://lv3wgjcyixc.feishu.cn/sheets/shtcngNygNfuqhxTBf588jabcef", "wiki");

    assertThat(resolved.objType()).isEqualTo("wiki");
  }

  @Test
  @DisplayName("resolveTokenAndObjType rejects null, blank, and whitespace-only input")
  void rejectsBlankInput() {
    assertThatThrownBy(() -> tools.resolveTokenAndObjType(null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveTokenAndObjType("", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.resolveTokenAndObjType("   ", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("resolveTokenAndObjType trims a bare token with surrounding whitespace")
  void trimsBareToken() {
    final var resolved = tools.resolveTokenAndObjType("  wikcnKQ1k3p8Vabcef  ", null);

    assertThat(resolved.token()).isEqualTo("wikcnKQ1k3p8Vabcef");
  }
}
