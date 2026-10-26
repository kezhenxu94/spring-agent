package me.kezhenxu94.springagent.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * What the HTTP side of this server serves, and to whom.
 *
 * <p>The same public paths as the Feishu and Slack servers, and nothing else. Those two log a
 * person in with their chat platform's OpenID Connect provider before serving the rest; Telegram
 * has no such provider, so there is nobody to log in and the rest is refused outright rather than
 * left behind a login nobody can complete. The bot itself is unaffected: it polls Telegram and is
 * served by nothing here.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfigurer {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.cors(Customizer.withDefaults())
        .csrf(it -> it.disable())
        .formLogin(it -> it.disable())
        .httpBasic(it -> it.disable())
        .authorizeHttpRequests(
            it ->
                // Webhooks carry no session and cannot log in; each source verifies a signature or
                // a shared token over the body instead. See WebhookController.
                it.requestMatchers("/actuator/**", "/share/public/**", "/events/webhooks/**")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
