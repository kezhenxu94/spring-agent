package me.kezhenxu94.springagent.appweb.security;

import java.util.ArrayList;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.appweb.config.WebProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;

/**
 * Who gets in: somebody whose Feishu tenant is the one this deployment was configured for.
 *
 * <p>The same rule {@code FeishuAuthoritiesMapper} applies, restated here rather than imported.
 * That class lives in {@code spring-agent-integration-feishu}, which brings the Feishu SDK, the
 * bot's websocket client, its card renderer and its two dozen tools — none of which this
 * application has any use for. What login actually needs from Feishu is an OAuth2 provider and a
 * tenant key to compare against, and both are configuration.
 *
 * <p>Refusal is loud on purpose. A rejected login is indistinguishable from a broken one to whoever
 * is looking at the page — both are a signed-in person who cannot do anything — so the log has to
 * say which it was, and with what. {@code /api/me} tells the person the same thing in the page; see
 * {@link WebSecurityConfigurer} for why that one endpoint is reachable without the role.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebAuthoritiesMapper implements GrantedAuthoritiesMapper {

  /** What {@code WebSecurityConfigurer} requires of every request that is not public. */
  public static final String ROLE = "ROLE_SEAMAN";

  /** What Feishu calls the enterprise a person belongs to. */
  public static final String TENANT_ATTRIBUTE = "tenant_key";

  private final WebProperties properties;

  @Override
  public Collection<? extends GrantedAuthority> mapAuthorities(
      final Collection<? extends GrantedAuthority> authorities) {
    final var mapped = new ArrayList<GrantedAuthority>();
    var admitted = false;
    for (final var authority : authorities) {
      // Carried through rather than replaced. The user service contributes a SCOPE_* authority per
      // granted scope alongside the profile one, and mapping those to ROLE_USER would throw away
      // what the token actually permits.
      mapped.add(authority);
      if (authority instanceof OAuth2UserAuthority profile && admits(profile)) {
        admitted = true;
      }
    }
    mapped.add(new SimpleGrantedAuthority(admitted ? ROLE : "ROLE_USER"));
    return mapped;
  }

  private boolean admits(final OAuth2UserAuthority profile) {
    final var expected = properties.auth().tenantId();
    final var attributes = profile.getAttributes();
    final var actual = attributes.get(TENANT_ATTRIBUTE);

    if (expected.isEmpty()) {
      // Deliberately not a free pass. A deployment that has not said which tenant it serves cannot
      // tell a colleague from a stranger who happened to install the same Feishu app, and letting
      // the second one drive an agent that holds the first one's credentials is the failure this
      // check exists to prevent.
      log.warn(
          "Refusing {}: app.web.auth.tenant-id is not set, so there is nothing to admit them"
              + " against. Set it (FEISHU_TENANT_ID) to this deployment's tenant_key — the profile"
              + " just returned tenant_key={}.",
          attributes.get("open_id"),
          actual);
      return false;
    }
    if (expected.equals(actual)) {
      return true;
    }
    // WARN rather than INFO, and with both values: this is the one refusal a correctly configured
    // deployment should never see, and the one a misconfigured deployment sees on every login.
    log.warn(
        "Refusing {}: their tenant_key is {}, and app.web.auth.tenant-id is {}. The profile"
            + " returned these attributes: {}.",
        attributes.get("open_id"),
        actual == null ? "absent from the profile" : actual,
        expected,
        attributes.keySet());
    return false;
  }
}
