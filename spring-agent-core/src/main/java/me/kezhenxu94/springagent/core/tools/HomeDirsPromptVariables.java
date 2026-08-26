package me.kezhenxu94.springagent.core.tools;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.core.agent.AgentRequest;
import me.kezhenxu94.springagent.core.agent.PromptVariablesContributor;
import me.kezhenxu94.springagent.core.config.CoreMessages;
import org.springframework.stereotype.Component;

/**
 * Tells the model which homes this run reaches, and what each one is for.
 *
 * <p>The tools already span every scope — the filesystem sandbox is allowed into each home, the
 * skills index reads all of them — but permission is not knowledge: without being told, the model
 * has no way to name a directory it was never handed a path to, and would keep writing everything
 * into the user's own home. So the same {@code {homeDirs}} block that says where things go also
 * says who else can see them, which is what makes putting a file in a shared home a choice rather
 * than an accident.
 */
@Component
@RequiredArgsConstructor
public class HomeDirsPromptVariables implements PromptVariablesContributor {
  public static final String VARIABLE = "homeDirs";

  private final UserWorkspaceFactory userWorkspaceFactory;
  private final CoreMessages messages;

  @Override
  public Map<String, Object> variables(final AgentRequest request) {
    if (Strings.isNullOrEmpty(request.userId())) {
      return Map.of();
    }

    final var lines = new ArrayList<String>();
    lines.add(describe(userWorkspaceFactory.forOwner(request.userId()), "home-dir-own"));
    if (!Strings.isNullOrEmpty(request.groupId())) {
      lines.add(describe(userWorkspaceFactory.forGroup(request.groupId()), "home-dir-group"));
    }
    if (!Strings.isNullOrEmpty(request.tenantId())) {
      lines.add(describe(userWorkspaceFactory.forTenant(request.tenantId()), "home-dir-tenant"));
    }
    return Map.of(VARIABLE, String.join("\n", lines));
  }

  /**
   * A home is described by its root and the folders it may hold, not by what is in it: the
   * directories are made when something is first written to them, so listing what exists now would
   * be a snapshot the model cannot act on.
   *
   * <p>Through the bundle, because this lands inside the application's system prompt: a deployment
   * that wrote that prompt in its own language would otherwise find three English sentences in the
   * middle of it.
   *
   * @param audience the message key naming who else can see this home
   */
  private String describe(final UserHome home, final String audience) {
    return messages.get("home-dir", home.root(), messages.get(audience));
  }
}
