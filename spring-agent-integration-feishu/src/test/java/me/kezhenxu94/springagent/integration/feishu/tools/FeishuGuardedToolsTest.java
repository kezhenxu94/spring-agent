package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * That the table {@link FeishuAccessInterceptor} rules by still describes the tools that exist.
 *
 * <p>This is the load-bearing test of the whole access check, and it is a build-time test rather
 * than a runtime one on purpose. The interceptor refuses a Feishu tool it has no rule for, which
 * makes an unguarded tool safe but useless — so without this, adding a tool and forgetting the
 * table would be discovered by a person in a chat rather than by the build. And the second half
 * matters as much: a rule naming an argument the tool does not take reads every call as "no token
 * given" and, where the argument is optional, waves the call through. That failure is silent, looks
 * exactly like working, and is the one this test exists to make impossible.
 *
 * <p>The inventory is built here rather than borrowed from {@code ToolTextsInventory}: the check
 * that nothing can quietly stop being guarded should not itself depend on a helper written for
 * something else.
 */
class FeishuGuardedToolsTest {

  private static final String BASE_PACKAGE = "me.kezhenxu94.springagent.integration.feishu";

  /** Every type Feishu's permission endpoints accept, plus this module's one pseudo-type. */
  private static final Set<String> KNOWN_TYPES =
      Set.of(
          FeishuGuardedTools.DOCX,
          FeishuGuardedTools.SHEET,
          FeishuGuardedTools.BITABLE,
          FeishuGuardedTools.FILE,
          FeishuGuardedTools.FOLDER,
          FeishuGuardedTools.WIKI,
          FeishuGuardedTools.WIKI_SPACE);

  /**
   * Tool name to the parameters it takes, {@code ToolContext} excluded as the schema excludes it.
   */
  private static Map<String, Set<String>> tools;

  @BeforeAll
  static void inventory() throws Exception {
    final var types = new ArrayList<Class<?>>();
    final var resolver = new PathMatchingResourcePatternResolver();
    final var readers = new CachingMetadataReaderFactory(resolver);
    final var pattern =
        "classpath*:" + ClassUtils.convertClassNameToResourcePath(BASE_PACKAGE) + "/**/*.class";
    for (final var resource : resolver.getResources(pattern)) {
      final var className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
      try {
        types.add(ClassUtils.forName(className, FeishuGuardedToolsTest.class.getClassLoader()));
      } catch (Throwable ignored) {
        // A class whose own dependencies are absent cannot be a tool this module offers.
      }
    }

    final var found = new HashMap<String, Set<String>>();
    for (final var type : types) {
      for (final var method : type.getDeclaredMethods()) {
        final var tool = method.getAnnotation(Tool.class);
        if (tool == null) {
          continue;
        }
        final var name = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
        found.put(
            name,
            Arrays.stream(method.getParameters())
                .filter(p -> !ToolContext.class.isAssignableFrom(p.getType()))
                .map(Parameter::getName)
                .collect(Collectors.toUnmodifiableSet()));
      }
    }
    tools = found;
  }

  @Test
  @DisplayName("the inventory found the tools, so nothing below passes vacuously")
  void inventoryIsNotEmpty() {
    assertThat(tools)
        .isNotEmpty()
        .containsKeys(
            "FeishuCreateDocument",
            "FeishuGetDocumentRawContent",
            "FeishuSheetReadRange",
            "FeishuSearchBitableRecords",
            "FeishuListWikiNodes",
            "FeishuMyDriveFolder");
  }

  @Test
  @DisplayName("every Feishu tool is either guarded or explicitly declared to carry no token")
  void everyToolIsRuledOn() {
    assertSoftly(
        softly ->
            tools
                .keySet()
                .forEach(
                    tool ->
                        softly
                            .assertThat(
                                FeishuGuardedTools.GUARDED.containsKey(tool)
                                    || FeishuGuardedTools.UNGUARDED.contains(tool))
                            .as(
                                "%s has no access rule. Add it to FeishuGuardedTools.GUARDED with"
                                    + " the argument carrying its token, or to UNGUARDED if it"
                                    + " carries none. Until then every call to it is refused.",
                                tool)
                            .isTrue()));
  }

  @Test
  @DisplayName("no tool is in both lists, which would make which one wins an accident of lookup")
  void noToolIsInBothLists() {
    final var both =
        FeishuGuardedTools.GUARDED.keySet().stream()
            .filter(FeishuGuardedTools.UNGUARDED::contains)
            .toList();
    assertThat(both).as("in both GUARDED and UNGUARDED").isEmpty();
  }

  @Test
  @DisplayName("every rule names a tool that still exists")
  void everyRuleNamesALiveTool() {
    assertSoftly(
        softly -> {
          FeishuGuardedTools.GUARDED
              .keySet()
              .forEach(
                  tool ->
                      softly
                          .assertThat(tools)
                          .as("GUARDED names %s, which is no longer a tool", tool)
                          .containsKey(tool));
          FeishuGuardedTools.UNGUARDED.forEach(
              tool ->
                  softly
                      .assertThat(tools)
                      .as("UNGUARDED names %s, which is no longer a tool", tool)
                      .containsKey(tool));
        });
  }

  @Test
  @DisplayName("every guarded argument is a parameter the tool actually takes")
  void everyGuardedArgumentExists() {
    assertSoftly(
        softly ->
            FeishuGuardedTools.GUARDED.forEach(
                (tool, rules) -> {
                  final var parameters = tools.get(tool);
                  if (parameters == null) {
                    return;
                  }
                  rules.forEach(
                      rule ->
                          softly
                              .assertThat(parameters)
                              .as(
                                  "%s is guarded on '%s', which it does not take — every call would"
                                      + " read as having no token",
                                  tool, rule.argument())
                              .contains(rule.argument()));
                }));
  }

  @Test
  @DisplayName("every type is one Feishu understands, or the argument that carries it exists")
  void everyTypeResolves() {
    assertSoftly(
        softly ->
            FeishuGuardedTools.GUARDED.forEach(
                (tool, rules) -> {
                  final var parameters = tools.getOrDefault(tool, Set.of());
                  rules.forEach(
                      rule -> {
                        if (!rule.type().startsWith("$")) {
                          softly
                              .assertThat(KNOWN_TYPES)
                              .as(
                                  "%s is guarded as type '%s', which Feishu has no name for",
                                  tool, rule.type())
                              .contains(rule.type());
                          return;
                        }
                        final var spec = rule.type().substring(1);
                        final var separator = spec.indexOf('|');
                        final var argument = separator < 0 ? spec : spec.substring(0, separator);
                        softly
                            .assertThat(parameters)
                            .as(
                                "%s reads its type from '%s', which it does not take",
                                tool, argument)
                            .contains(argument);
                        if (separator >= 0) {
                          softly
                              .assertThat(KNOWN_TYPES)
                              .as(
                                  "%s falls back to type '%s', which Feishu has no name for",
                                  tool, spec.substring(separator + 1))
                              .contains(spec.substring(separator + 1));
                        }
                      });
                }));
  }

  @Test
  @DisplayName("every tool that takes a token-shaped argument is guarded on it")
  void nothingTokenShapedIsWavedThrough() {
    // The names this module gives to something on the Feishu side. A tool taking one of these and
    // sitting in UNGUARDED is the exact mistake this whole file is about, so it is spelled out
    // rather than left to a reviewer to notice.
    final var tokenShaped =
        Set.of(
            "documentId",
            "spreadsheetToken",
            "appToken",
            "fileToken",
            "folderToken",
            "folderURL",
            "spaceId",
            "urlOrToken");
    assertSoftly(
        softly ->
            FeishuGuardedTools.UNGUARDED.forEach(
                tool -> {
                  final var parameters = tools.getOrDefault(tool, Set.of());
                  final var offending = parameters.stream().filter(tokenShaped::contains).toList();
                  softly
                      .assertThat(offending)
                      .as(
                          "%s is UNGUARDED but takes %s, which names something on Feishu",
                          tool, offending)
                      .isEmpty();
                }));
  }

  @Test
  @DisplayName("a bare token resolves to itself, whatever type it was declared as")
  void bareTokenResolvesToItself() {
    final var resolved = FeishuGuardedTools.resolve("doccnXYZ", FeishuGuardedTools.DOCX);
    assertThat(resolved.token()).isEqualTo("doccnXYZ");
    assertThat(resolved.type()).isEqualTo(FeishuGuardedTools.DOCX);
  }

  @Test
  @DisplayName("a link resolves to the token in it, and to the kind the link says")
  void linkResolvesToItsToken() {
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/docx/DOC1", null))
        .isEqualTo(new FeishuGuardedTools.Resolved("DOC1", "docx"));
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/sheets/SH1", null))
        .isEqualTo(new FeishuGuardedTools.Resolved("SH1", FeishuGuardedTools.SHEET));
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/base/BT1", null))
        .isEqualTo(new FeishuGuardedTools.Resolved("BT1", FeishuGuardedTools.BITABLE));
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/drive/folder/FLD1", null))
        .isEqualTo(new FeishuGuardedTools.Resolved("FLD1", FeishuGuardedTools.FOLDER));
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/wiki/WK1", null))
        .isEqualTo(new FeishuGuardedTools.Resolved("WK1", FeishuGuardedTools.WIKI));
  }

  @Test
  @DisplayName("a declared type wins over the kind the link says")
  void declaredTypeWins() {
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/wiki/WK1", FeishuGuardedTools.DOCX))
        .isEqualTo(new FeishuGuardedTools.Resolved("WK1", FeishuGuardedTools.DOCX));
  }

  @Test
  @DisplayName("a query string, a fragment and a trailing path are not part of the token")
  void linkNoiseIsNotPartOfTheToken() {
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/docx/DOC1?from=chat", null).token())
        .isEqualTo("DOC1");
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/docx/DOC1#block", null).token())
        .isEqualTo("DOC1");
    assertThat(FeishuGuardedTools.resolve("https://x.feishu.cn/docx/DOC1/extra", null).token())
        .isEqualTo("DOC1");
  }

  @Test
  @DisplayName("surrounding whitespace does not become part of the token")
  void whitespaceIsTrimmed() {
    assertThat(FeishuGuardedTools.resolve("  doccnXYZ  ", FeishuGuardedTools.DOCX).token())
        .isEqualTo("doccnXYZ");
  }

  @Test
  @DisplayName("nothing in, nothing out; the caller decides what an absent argument means")
  void blankResolvesToBlank() {
    assertThat(FeishuGuardedTools.resolve(null, FeishuGuardedTools.DOCX).token()).isNull();
    assertThat(FeishuGuardedTools.resolve("", FeishuGuardedTools.DOCX).token()).isEmpty();
  }

  @Test
  @DisplayName("the guard and the folder tool read a link the same way")
  void theGuardAndTheFolderToolAgree() {
    final var links =
        List.of(
            "https://x.feishu.cn/drive/folder/FLD1",
            "https://x.feishu.cn/drive/folder/FLD1?from=space",
            "FLD1");
    for (final var link : links) {
      assertThat(FeishuGuardedTools.resolve(link, FeishuGuardedTools.FOLDER).token())
          .as("the guard would check a folder the tool would not open: %s", link)
          .isEqualTo("FLD1");
    }
  }
}
