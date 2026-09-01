package me.kezhenxu94.springagent.integration.websocket.config;

import me.kezhenxu94.springagent.integration.websocket.aot.WebRuntimeHints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Wires the browser surface: the page, the REST endpoints behind it, and the STOMP broker a run
 * streams over.
 *
 * <p>Ungated on purpose. Every other optional module here carries a switch because taking it costs
 * something a deployment might not want — a websocket held open to Feishu, a mailbox polled, an
 * unauthenticated webhook path. This one serves a page and answers requests about the caller's own
 * conversations; depending on it *is* the decision, and a property that turned it off would only
 * describe a jar nobody should have taken.
 *
 * <p><b>What this module deliberately does not contribute is a {@code SecurityFilterChain}.</b> The
 * consuming application owns that, the same way {@code spring-agent-integration-feishu} leaves it
 * to {@code spring-agent-app-feishu}: who may log in, which OAuth2 registration the sign-in button
 * goes to, and which of the application's *other* paths are public are all decisions this module
 * cannot make for it. What it does contribute is {@code WebAuthoritiesMapper}, the rule for
 * admitting a person, which an application wires into its own {@code oauth2Login}. See {@code
 * spring-agent-app-webui}'s {@code SecurityConfigurer} for the arrangement this module expects, and
 * for why CSRF is on there.
 *
 * <p><b>A consuming application must also carry {@code @EnableScheduling}.</b> {@link
 * me.kezhenxu94.springagent.integration.websocket.run.RunJournals} sweeps finished runs on a timer,
 * and Boot auto-configures a scheduler but never the annotation that makes {@code @Scheduled} mean
 * anything. Without it a journal is still evicted once the {@code app.web.journal.max-runs} cap is
 * reached — {@code open} enforces that inline — but {@code app.web.journal.retention} stops meaning
 * anything, so a quiet server holds every run it has ever served until the cap does the work.
 */
@AutoConfiguration(
    // Before Spring MVC's, and this is load-bearing rather than tidy. WebLocaleConfiguration
    // contributes a bean named `localeResolver`, which is the fixed name DispatcherServlet looks
    // one up by, and Boot's WebMvcAutoConfiguration contributes its own under that name
    // @ConditionalOnMissingBean. Whichever registers first wins and the other fails the context
    // outright, so the order cannot be left to chance — which it was while this module was an
    // application's own @ComponentScan, since user configuration is always registered first.
    beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.websocket",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = WebSocketAutoConfiguration.class))
@EnableConfigurationProperties(WebProperties.class)
@ImportRuntimeHints(WebRuntimeHints.class)
public class WebSocketAutoConfiguration {}
