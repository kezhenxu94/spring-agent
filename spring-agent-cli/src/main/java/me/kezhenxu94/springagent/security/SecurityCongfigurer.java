package me.kezhenxu94.springagent.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityCongfigurer {

  final FeishuAuthoritiesMapper feishuAuthoritiesMapper;

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
                it.userInfoEndpoint(
                    config -> config.userAuthoritiesMapper(feishuAuthoritiesMapper)))
        .build();
  }
}
