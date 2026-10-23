package me.kezhenxu94.springagent.integration.websocket.config;

import me.kezhenxu94.springagent.core.config.UserModelsConfiguration;
import me.kezhenxu94.springagent.core.usermodels.UserModelRegistry;
import me.kezhenxu94.springagent.integration.websocket.web.UserModelController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Import;

/**
 * Registers {@link UserModelController}, only where core registered a {@link UserModelRegistry}.
 *
 * <p>An auto-configuration of its own rather than a condition on the controller, because a
 * condition on a class {@link WebSocketAutoConfiguration}'s scan finds is evaluated while
 * configuration classes are still being parsed — before any {@code @Bean} method, core's registry
 * included, has defined anything. There {@code @ConditionalOnBean} always fails: the controller was
 * dropped, {@code /api/me} still reported models enabled, and the page's Model tab got a 404. On an
 * auto-configuration the condition is evaluated as its bean definitions are loaded, in the order
 * {@code after} sets, so the registry is already there to be found.
 */
@AutoConfiguration(after = UserModelsConfiguration.class)
@ConditionalOnBean(UserModelRegistry.class)
@Import(UserModelController.class)
public class UserModelWebConfiguration {}
