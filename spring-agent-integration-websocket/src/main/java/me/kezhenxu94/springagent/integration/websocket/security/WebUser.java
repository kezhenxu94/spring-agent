package me.kezhenxu94.springagent.integration.websocket.security;

import com.google.common.base.Strings;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Who is asking, read off the OAuth2 principal.
 *
 * <p>Always from the principal and never from a path or a body. Every identity the agent acts under
 * — whose home directory it reads, whose credentials it uses, whose knowledge base it searches — is
 * derived from these two fields, so a request that could name its own user id would be a request
 * that could act as anyone.
 *
 * @param id the Feishu {@code open_id}, which is what every other surface here means by a user id,
 *     so a person's files and conversations are the same whichever way they reach the agent
 */
public record WebUser(String id, String name, String avatar, String tenantId) {

  public static WebUser of(final OAuth2User principal) {
    if (principal == null) {
      return null;
    }
    final var attributes = principal.getAttributes();
    final var id = string(attributes.get("open_id"));
    if (id.isEmpty()) {
      // Not defaulted to the name or the email: an id that is not stable is worse than none, since
      // it would silently hand one person another's conversations the day it changed.
      throw new IllegalStateException("The Feishu profile carried no open_id");
    }
    return new WebUser(
        id,
        string(attributes.get("name")),
        string(attributes.get("avatar_url")),
        string(attributes.get("tenant_key")));
  }

  private static String string(final Object value) {
    return value == null ? "" : Strings.nullToEmpty(String.valueOf(value));
  }
}
