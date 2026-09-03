package me.kezhenxu94.springagent.integration.feishu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeishuMarkdownTest {

  @Test
  @DisplayName("a quoted message cannot notify a whole group")
  void neutralisesAtAll() {
    // The case this class exists for: typed into the web composer and mirrored into a Feishu
    // group, an unescaped one of these pings every member on a stranger's say-so.
    assertThat(FeishuMarkdown.escaped("<at id=all></at> ship it"))
        .doesNotContain("<at")
        .isEqualTo("&#60;at id=all&#62;&#60;/at&#62; ship it");
  }

  @Test
  @DisplayName("a quoted message cannot notify one person either")
  void neutralisesAMention() {
    assertThat(FeishuMarkdown.escaped("ask <at id=ou_1></at>")).doesNotContain("<at id=ou_1>");
  }

  @Test
  @DisplayName("ampersands are escaped first, so the entities we write are not escaped again")
  void escapesAmpersandsBeforeTheRest() {
    // Both halves of the ordering. A literal ampersand survives as one...
    assertThat(FeishuMarkdown.escaped("this & that")).isEqualTo("this &amp; that");
    // ...and text that already looked like an entity stays the characters the author typed,
    // rather than becoming the character it names.
    assertThat(FeishuMarkdown.escaped("&#60;")).isEqualTo("&amp;&#35;60;");
  }

  @Test
  @DisplayName("every character the dialect acts on renders as itself")
  void escapesTheDocumentedList() {
    assertThat(FeishuMarkdown.escaped("*b* _i_ ~s~ #h `c`"))
        .isEqualTo("&#42;b&#42; &#95;i&#95; &sim;s&sim; &#35;h &#96;c&#96;");
  }

  @Test
  @DisplayName("nothing to quote is not a null to pass on")
  void handlesNothing() {
    assertThat(FeishuMarkdown.escaped(null)).isEmpty();
    assertThat(FeishuMarkdown.escaped("")).isEmpty();
  }

  @Test
  @DisplayName("ordinary prose is left alone")
  void leavesProseAlone() {
    assertThat(FeishuMarkdown.escaped("how do I roll back the canary?"))
        .isEqualTo("how do I roll back the canary?");
  }
}
