package me.kezhenxu94.springagent.integration.feishu.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import me.kezhenxu94.springagent.core.tools.ToolContexts;
import me.kezhenxu94.springagent.core.tools.interceptors.ToolCallInterceptor;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuMessages;
import me.kezhenxu94.springagent.integration.feishu.config.FeishuProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * That the guard actually stands in front of every Feishu tool, and stands in front of the right
 * thing.
 *
 * <p>Two failure modes are worth more than the rest here, and both are silent. A tool that slips
 * past the table works perfectly and checks nothing — {@link #everyGuardedToolIsRefusedWhenAccessIs
 * Denied} is the sweep that would catch it. And a refusal that leaves as an ordinary exception ends
 * the turn instead of answering it, which reads to the person as a broken agent rather than as a
 * permission they lack; every refusal here is therefore asserted to be a {@link
 * ToolCallInterceptor.CallRefused}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeishuAccessInterceptorTest {

  private static final ToolContext CONTEXT =
      new ToolContext(Map.of(ToolContexts.KEY_USER_ID, "ou_asker"));

  @Mock private FeishuDriveAccess driveAccess;

  private FeishuAccessInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new FeishuAccessInterceptor(
            driveAccess,
            new JsonMapper(),
            new FeishuMessages(
                new FeishuProperties(
                    null, null, null, null, null, null, null, Locale.ENGLISH, null, null, null)));
  }

  private void denyEverything() {
    doThrow(new FeishuDriveAccess.DriveAccessDeniedException("Refused: you do not have access"))
        .when(driveAccess)
        .requireAccess(any(), any(), any());
    doThrow(new FeishuDriveAccess.DriveAccessDeniedException("Refused: you are not a member"))
        .when(driveAccess)
        .requireWikiSpaceAccess(any(), any());
  }

  @Test
  @DisplayName("a tool from another module is none of this bean's business")
  void aNonFeishuToolPassesThrough() {
    final var input = "{\"path\":\"/etc/passwd\"}";

    assertThat(interceptor.beforeCall("ReadFile", input, CONTEXT)).isEqualTo(input);

    verifyNoInteractions(driveAccess);
  }

  @Test
  @DisplayName("a Feishu tool nobody has written a rule for is refused rather than let through")
  void anUnknownFeishuToolIsRefused() {
    assertThatThrownBy(() -> interceptor.beforeCall("FeishuSomethingNew", "{}", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class)
        .hasMessageContaining("FeishuSomethingNew")
        .hasMessageContaining("no access rule");

    verifyNoInteractions(driveAccess);
  }

  @Test
  @DisplayName("a tool declared to carry no token is not checked")
  void anUnguardedToolIsNotChecked() {
    final var input = "{}";

    assertThat(interceptor.beforeCall("FeishuDocBlockGuide", input, CONTEXT)).isEqualTo(input);

    verifyNoInteractions(driveAccess);
  }

  @Test
  @DisplayName("a guarded tool is checked on the argument and type the table names")
  void aGuardedToolIsCheckedOnTheRightThing() {
    interceptor.beforeCall("FeishuGetDocumentRawContent", "{\"documentId\":\"doccnA\"}", CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("doccnA"), eq("docx"));
  }

  @Test
  @DisplayName("a denial comes back as a refusal the model can read, not as a thrown run")
  void aDenialBecomesARefusal() {
    denyEverything();

    assertThatThrownBy(
            () ->
                interceptor.beforeCall(
                    "FeishuGetDocumentRawContent", "{\"documentId\":\"doccnA\"}", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class)
        .hasMessageContaining("do not have access");
  }

  @Test
  @DisplayName("an optional folder left out is the person's own, so there is nothing to check")
  void anAbsentOptionalArgumentIsNotChecked() {
    assertThatCode(
            () -> interceptor.beforeCall("FeishuCreateDocument", "{\"title\":\"Notes\"}", CONTEXT))
        .doesNotThrowAnyException();

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("an optional folder that is given is checked like any other")
  void aGivenOptionalArgumentIsChecked() {
    interceptor.beforeCall(
        "FeishuCreateDocument", "{\"title\":\"Notes\",\"folderToken\":\"fldOTHER\"}", CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("fldOTHER"), eq("folder"));
  }

  @Test
  @DisplayName("an optional folder given as an empty string is not a token to check")
  void aBlankOptionalArgumentIsNotChecked() {
    interceptor.beforeCall(
        "FeishuCreateDocument", "{\"title\":\"Notes\",\"folderToken\":\"\"}", CONTEXT);

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("a required argument left out is refused rather than skipped")
  void anAbsentRequiredArgumentIsRefused() {
    assertThatThrownBy(() -> interceptor.beforeCall("FeishuGetDocumentInfo", "{}", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class)
        .hasMessageContaining("documentId");

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("arguments that will not parse leave every rule seeing nothing, so required refuses")
  void malformedArgumentsFailClosed() {
    assertThatThrownBy(
            () -> interceptor.beforeCall("FeishuGetDocumentInfo", "not json at all", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class);

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("an empty call to a required-argument tool is refused too")
  void emptyInputFailsClosed() {
    assertThatThrownBy(() -> interceptor.beforeCall("FeishuGetDocumentInfo", "", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class);
  }

  @Test
  @DisplayName("a link is checked as the token inside it, not as the whole URL")
  void aLinkIsCheckedAsItsToken() {
    interceptor.beforeCall(
        "FeishuListDriveFolder",
        "{\"folderURL\":\"https://x.feishu.cn/drive/folder/FLD1?from=chat\"}",
        CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("FLD1"), eq("folder"));
  }

  @Test
  @DisplayName("an export is checked as the kind of document it says it is exporting")
  void anExportIsCheckedAsItsDeclaredType() {
    interceptor.beforeCall(
        "FeishuExportDocument",
        "{\"token\":\"shtSHEET\",\"type\":\"sheet\",\"fileExtension\":\"xlsx\"}",
        CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("shtSHEET"), eq("sheet"));
  }

  @Test
  @DisplayName("an export that does not say what it is exporting is refused")
  void anExportWithNoTypeIsRefused() {
    assertThatThrownBy(
            () ->
                interceptor.beforeCall("FeishuExportDocument", "{\"token\":\"shtSHEET\"}", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class)
        .hasMessageContaining("type");

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("a wiki node with no type given is checked as a wiki node")
  void aWikiNodeDefaultsToWiki() {
    interceptor.beforeCall("FeishuGetWikiNodeInfo", "{\"urlOrToken\":\"wikcnNODE\"}", CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("wikcnNODE"), eq("wiki"));
  }

  @Test
  @DisplayName("a wiki node given as a document link is checked as that document")
  void aWikiNodeGivenAsADocumentLink() {
    interceptor.beforeCall(
        "FeishuGetWikiNodeInfo", "{\"urlOrToken\":\"https://x.feishu.cn/sheets/SH1\"}", CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("SH1"), eq("sheet"));
  }

  @Test
  @DisplayName("a declared objType wins over what the link looks like")
  void aDeclaredObjTypeWins() {
    interceptor.beforeCall(
        "FeishuGetWikiNodeInfo",
        "{\"urlOrToken\":\"https://x.feishu.cn/wiki/WK1\",\"objType\":\"docx\"}",
        CONTEXT);

    verify(driveAccess).requireAccess(eq(CONTEXT), eq("WK1"), eq("docx"));
  }

  @Test
  @DisplayName("a wiki space goes to the member check, not to the collaborator one")
  void aWikiSpaceGoesToTheSpaceCheck() {
    interceptor.beforeCall("FeishuListWikiNodes", "{\"spaceId\":\"7100\"}", CONTEXT);

    verify(driveAccess).requireWikiSpaceAccess(eq(CONTEXT), eq("7100"));
    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("walking a node inside a space is checked on both the space and the node")
  void bothTheSpaceAndTheNodeAreChecked() {
    interceptor.beforeCall(
        "FeishuListWikiNodes", "{\"spaceId\":\"7100\",\"parentNodeToken\":\"wikcnNODE\"}", CONTEXT);

    verify(driveAccess).requireWikiSpaceAccess(eq(CONTEXT), eq("7100"));
    verify(driveAccess).requireAccess(eq(CONTEXT), eq("wikcnNODE"), eq("wiki"));
  }

  @Test
  @DisplayName("a refused space stops the call before the node is even asked about")
  void aRefusedSpaceStopsTheCall() {
    denyEverything();

    assertThatThrownBy(
            () ->
                interceptor.beforeCall(
                    "FeishuListWikiNodes",
                    "{\"spaceId\":\"7100\",\"parentNodeToken\":\"wikcnNODE\"}",
                    CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class);

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("an argument that merely looks like the guarded one is not mistaken for it")
  void aSimilarlyNamedArgumentIsNotTheGuardedOne() {
    assertThatThrownBy(
            () ->
                interceptor.beforeCall(
                    "FeishuGetDocumentInfo", "{\"documentIdentifier\":\"doccnA\"}", CONTEXT))
        .isInstanceOf(ToolCallInterceptor.CallRefused.class);

    verify(driveAccess, never()).requireAccess(any(), any(), any());
  }

  @Test
  @DisplayName("afterCall does not touch the result, so nothing is rewritten on the way back")
  void afterCallIsAPassThrough() {
    assertThat(interceptor.afterCall("FeishuGetDocumentInfo", "{}", "the result", CONTEXT))
        .isEqualTo("the result");
  }

  /**
   * Every guarded tool, with an argument value for each rule it carries. Required arguments get a
   * token; optional ones do too, so that the sweep below exercises the check rather than the "left
   * out, nothing to do" path.
   */
  static Stream<String> everyGuardedTool() {
    return FeishuGuardedTools.GUARDED.keySet().stream().sorted();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyGuardedTool")
  @DisplayName("every guarded tool refuses when the check says no")
  void everyGuardedToolIsRefusedWhenAccessIsDenied(final String tool) {
    denyEverything();

    final var rules = FeishuGuardedTools.GUARDED.get(tool);
    final var arguments = new java.util.LinkedHashMap<String, String>();
    for (final var rule : rules) {
      arguments.put(rule.argument(), tokenFor(rule));
      if (rule.type().startsWith("$")) {
        final var spec = rule.type().substring(1);
        final var separator = spec.indexOf('|');
        // The type argument is filled in only where there is no fallback, so the tools that infer
        // their type are exercised through the inference rather than around it.
        if (separator < 0) {
          arguments.put(spec, "docx");
        }
      }
    }

    assertThatThrownBy(() -> interceptor.beforeCall(tool, json(arguments), CONTEXT))
        .as(
            "%s let a call through even though the access check refused it — its rules are %s",
            tool, rules)
        .isInstanceOf(ToolCallInterceptor.CallRefused.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyGuardedTool")
  @DisplayName("every guarded tool asks the check about every token it was given")
  void everyGuardedToolAsksAboutEveryToken(final String tool) {
    final var rules = FeishuGuardedTools.GUARDED.get(tool);
    final var arguments = new java.util.LinkedHashMap<String, String>();
    for (final var rule : rules) {
      arguments.put(rule.argument(), tokenFor(rule));
      if (rule.type().startsWith("$")) {
        final var spec = rule.type().substring(1);
        if (spec.indexOf('|') < 0) {
          arguments.put(spec, "docx");
        }
      }
    }

    interceptor.beforeCall(tool, json(arguments), CONTEXT);

    for (final var rule : rules) {
      if (FeishuGuardedTools.WIKI_SPACE.equals(rule.type())) {
        verify(driveAccess).requireWikiSpaceAccess(eq(CONTEXT), eq(tokenFor(rule)));
      } else {
        verify(driveAccess).requireAccess(eq(CONTEXT), eq(tokenFor(rule)), any());
      }
    }
  }

  private static String tokenFor(final FeishuGuardedTools.Guarded rule) {
    return "TOKEN_" + rule.argument();
  }

  private static String json(final Map<String, String> arguments) {
    return arguments.entrySet().stream()
        .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  @Test
  @DisplayName("the sweep above covers every guarded tool, so it cannot pass vacuously")
  void theSweepIsNotEmpty() {
    assertThat(everyGuardedTool().toList())
        .hasSize(FeishuGuardedTools.GUARDED.size())
        .contains("FeishuGetDocumentInfo", "FeishuSheetReadRange", "FeishuListWikiNodes");
    assertThat(List.copyOf(FeishuGuardedTools.GUARDED.keySet())).hasSizeGreaterThan(40);
  }
}
