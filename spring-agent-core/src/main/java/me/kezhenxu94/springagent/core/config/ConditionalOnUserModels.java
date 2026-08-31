package me.kezhenxu94.springagent.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when this deployment can store a user's API token sealed, which is the only condition
 * under which users may register models of their own.
 *
 * <p>A condition of its own rather than {@code @ConditionalOnProperty}, because the key reaches an
 * application as {@code ${USER_MODELS_ENCRYPTION_KEY:}} and so is *present but empty* when nobody
 * set it. {@code @ConditionalOnProperty} would call that configured and switch the feature on with
 * a key that cannot seal anything.
 *
 * <p>Note for native images: conditions are evaluated during AOT processing, so whether users may
 * choose a model is fixed when the image is built rather than when it runs — the same caveat as
 * {@link ConditionalOnShellBackend}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(UserModelsCondition.class)
public @interface ConditionalOnUserModels {}
