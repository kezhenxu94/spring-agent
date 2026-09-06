package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.List;
import me.kezhenxu94.springagent.core.i18n.AbstractLocalizedToolsEndToEndTest;
import org.springaicommunity.agent.tools.ShellTools;

/**
 * The local shell backend's three tools, which are the library's and not core's.
 *
 * <p>Worth its own class because this is the backend a command line runs on — {@code
 * spring-agent-app-cli} sets {@code app.ai.tools.shell.type=local} — and the library's {@code Bash}
 * declares four thousand characters of English. Untranslated, that alone is a larger block of
 * English than the system prompt, offered on every turn of a conversation held in another language.
 */
class LocalShellToolsTranslatedTest extends AbstractLocalizedToolsEndToEndTest {

  @Override
  protected String bundleBase() {
    return "shell-local/tools";
  }

  @Override
  protected String promptDirectory() {
    return "shell-local/prompts/tools/";
  }

  @Override
  protected List<Object> tools() {
    return List.of(ShellTools.builder().build());
  }
}
