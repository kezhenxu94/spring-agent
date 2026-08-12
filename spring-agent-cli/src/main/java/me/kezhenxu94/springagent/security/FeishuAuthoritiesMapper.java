package me.kezhenxu94.springagent.security;

import static java.util.stream.Collectors.toList;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import me.kezhenxu94.springagent.bot.configuration.SpringAgentProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeishuAuthoritiesMapper implements GrantedAuthoritiesMapper {
  final SpringAgentProperties appConfiguration;

  @Override
  public Collection<? extends GrantedAuthority> mapAuthorities(
      Collection<? extends GrantedAuthority> authorities) {
    return authorities.stream()
        .map(
            authority -> {
              if (authority instanceof OAuth2UserAuthority auth) {
                final var attributes = auth.getAttributes();

                if (attributes.containsKey("tenant_key")) {
                  final var tenant = (String) attributes.get("tenant_key");
                  if (appConfiguration.feishu().tenantId().equals(tenant)) {
                    return new SimpleGrantedAuthority("ROLE_SEAMAN");
                  }
                }
              }
              return new SimpleGrantedAuthority("ROLE_USER");
            })
        .collect(toList());
  }
}
