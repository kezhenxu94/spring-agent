package me.kezhenxu94.springagent.app;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfigurer {

  /** Contributed by whichever integration owns identity — Feishu today. */
  final ObjectProvider<GrantedAuthoritiesMapper> authoritiesMapper;

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.cors(Customizer.withDefaults())
        .csrf(it -> it.disable())
        .formLogin(it -> it.disable())
        .authorizeHttpRequests(
            it ->
                it.requestMatchers("/actuator/**", "/share/public/**")
                    .permitAll()
                    .anyRequest()
                    .hasRole("SEAMAN"))
        .logout(it -> it.permitAll())
        .oauth2Login(
            it ->
                authoritiesMapper.ifAvailable(
                    mapper -> it.userInfoEndpoint(config -> config.userAuthoritiesMapper(mapper))))
        .build();
  }
}
