package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuWikiTools.NodePage;
import me.kezhenxu94.springagent.integration.feishu.tools.FeishuWikiTools.WikiNodeInfo;
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

  /**
   * A wiki laid out in memory, answering the walk one page at a time and recording what it asked.
   */
  private static final class FakeWiki implements FeishuWikiTools.NodePageFetcher {
    /** (spaceId, parentNodeToken) -> that level's nodes, in order. */
    private final Map<String, List<WikiNodeInfo>> levels = new LinkedHashMap<>();

    private final List<String> asked = new ArrayList<>();
    private final int pageSize;

    FakeWiki(final int pageSize) {
      this.pageSize = pageSize;
    }

    FakeWiki node(
        final String spaceId, final String parent, final String nodeToken, final boolean hasChild) {
      levels
          .computeIfAbsent(key(spaceId, parent), k -> new ArrayList<>())
          .add(
              WikiNodeInfo.builder()
                  .spaceId(spaceId)
                  .nodeToken(nodeToken)
                  .parentNodeToken(parent)
                  .title(nodeToken)
                  .hasChild(hasChild)
                  .build());
      return this;
    }

    FakeWiki shortcut(
        final String spaceId,
        final String parent,
        final String nodeToken,
        final String originSpaceId,
        final String originNodeToken) {
      levels
          .computeIfAbsent(key(spaceId, parent), k -> new ArrayList<>())
          .add(
              WikiNodeInfo.builder()
                  .spaceId(spaceId)
                  .nodeToken(nodeToken)
                  .parentNodeToken(parent)
                  .title(nodeToken)
                  .nodeType("shortcut")
                  .originSpaceId(originSpaceId)
                  .originNodeToken(originNodeToken)
                  .hasChild(true)
                  .build());
      return this;
    }

    @Override
    public NodePage fetch(
        final String spaceId, final String parentNodeToken, final String pageToken) {
      asked.add(key(spaceId, parentNodeToken));
      final var all = levels.getOrDefault(key(spaceId, parentNodeToken), List.of());
      final var from = pageToken == null ? 0 : Integer.parseInt(pageToken);
      final var to = Math.min(all.size(), from + pageSize);
      return new NodePage(List.copyOf(all.subList(from, to)), String.valueOf(to), to < all.size());
    }

    private static String key(final String spaceId, final String parent) {
      return spaceId + "/" + (parent == null ? "" : parent);
    }
  }

  @Test
  @DisplayName("listWikiNodes at depth 1 returns one page and asks for no children")
  void depthOneIsOnePage() {
    final var wiki = new FakeWiki(10).node("sp", null, "a", true).node("sp", null, "b", false);

    final var result = tools.listWikiNodes("sp", null, null, 1, wiki);

    assertThat(result.nodes()).extracting(WikiNodeInfo::nodeToken).containsExactly("a", "b");
    assertThat(result.truncated()).isFalse();
    assertThat(wiki.asked).containsExactly("sp/");
  }

  @Test
  @DisplayName("listWikiNodes reports the top level's pagination, not a deeper level's")
  void paginationDescribesTheTopLevel() {
    final var wiki = new FakeWiki(1).node("sp", null, "a", true).node("sp", null, "b", false);
    wiki.node("sp", "a", "a1", false).node("sp", "a", "a2", false);

    final var result = tools.listWikiNodes("sp", null, null, -1, wiki);

    // Only "a" comes from the top level, since its page holds one node; its children are walked
    // through in full regardless.
    assertThat(result.nodes()).extracting(WikiNodeInfo::nodeToken).containsExactly("a", "a1", "a2");
    assertThat(result.hasMore()).isTrue();
    assertThat(result.pageToken()).isEqualTo("1");
  }

  @Test
  @DisplayName("listWikiNodes walks only as deep as maxDepth")
  void stopsAtMaxDepth() {
    final var wiki = new FakeWiki(10).node("sp", null, "a", true);
    wiki.node("sp", "a", "a1", true).node("sp", "a1", "a1x", false);

    final var result = tools.listWikiNodes("sp", null, null, 2, wiki);

    assertThat(result.nodes()).extracting(WikiNodeInfo::nodeToken).containsExactly("a", "a1");
    assertThat(result.truncated()).isFalse();
    assertThat(wiki.asked).containsExactly("sp/", "sp/a");
  }

  @Test
  @DisplayName("listWikiNodes walks a whole subtree when maxDepth is negative")
  void walksTheWholeSubtree() {
    final var wiki = new FakeWiki(10).node("sp", null, "a", true);
    wiki.node("sp", "a", "a1", true).node("sp", "a1", "a1x", false);

    final var result = tools.listWikiNodes("sp", null, null, -1, wiki);

    assertThat(result.nodes())
        .extracting(WikiNodeInfo::nodeToken)
        .containsExactly("a", "a1", "a1x");
  }

  @Test
  @DisplayName("listWikiNodes follows a shortcut into the space it points at")
  void followsShortcutsIntoTheirOwnSpace() {
    final var wiki = new FakeWiki(10).shortcut("sp", null, "sc", "other", "origin");
    wiki.node("other", "origin", "o1", false);

    final var result = tools.listWikiNodes("sp", null, null, -1, wiki);

    assertThat(result.nodes()).extracting(WikiNodeInfo::nodeToken).containsExactly("sc", "o1");
    assertThat(wiki.asked).containsExactly("sp/", "other/origin");
  }

  @Test
  @DisplayName("listWikiNodes stops at the node cap and says the walk was truncated")
  void truncatesAtTheNodeCap() {
    final var wiki = new FakeWiki(50);
    // 20 top-level nodes, each with 50 children: 1020 nodes, well past the 500 cap.
    for (int i = 0; i < 20; i++) {
      wiki.node("sp", null, "n" + i, true);
      for (int j = 0; j < 50; j++) {
        wiki.node("sp", "n" + i, "n" + i + "-" + j, false);
      }
    }

    final var result = tools.listWikiNodes("sp", null, null, -1, wiki);

    assertThat(result.nodes()).hasSize(500);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  @DisplayName("listWikiNodes requires a space id")
  void rejectsBlankSpaceId() {
    assertThatThrownBy(() -> tools.listWikiNodes(null, null, null, 1, new FakeWiki(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.listWikiNodes("  ", null, null, 1, new FakeWiki(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("listWikiNodes rejects a page size the endpoint would refuse")
  void rejectsOutOfRangePageSize() {
    assertThatThrownBy(() -> tools.listWikiNodes("sp", null, null, 51, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.listWikiNodes("sp", null, null, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
