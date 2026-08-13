package me.kezhenxu94.springagent.integration.feishu.config;

import com.lark.oapi.Client;
import me.kezhenxu94.springagent.core.config.ConditionalOnPersistenceBackend;
import me.kezhenxu94.springagent.core.config.PersistenceProperties.Type;
import me.kezhenxu94.springagent.integration.feishu.aot.FeishuRuntimeHints;
import me.kezhenxu94.springagent.integration.feishu.aot.LarkSdkRuntimeHints;
import me.kezhenxu94.springagent.integration.feishu.dao.FeishuMessage;
import me.kezhenxu94.springagent.integration.feishu.dao.jpa.JpaFeishuMessageRepo;
import me.kezhenxu94.springagent.integration.feishu.dao.mongo.MongoFeishuMessageRepo;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Wires the Feishu integration: the Lark client plus every {@code @Component} under {@code
 * me.kezhenxu94.springagent.integration.feishu}.
 *
 * <p>Set {@code app.feishu.enabled=false} to leave all of it out — importantly including {@link
 * FeishuEventHandler}, which opens a websocket to Feishu as soon as it is created. The switch is a
 * dedicated flag rather than a check on {@code app.feishu.app-id} because conditions are evaluated
 * against raw property values, and the credentials are configured as {@code ${FEISHU_APP_ID}}
 * placeholders that fail to resolve precisely when Feishu is not set up.
 */
@AutoConfiguration
@ComponentScan(
    basePackages = "me.kezhenxu94.springagent.integration.feishu",
    // Without this, the scan would also register this class, which is already imported.
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = FeishuAutoConfiguration.class))
@EnableConfigurationProperties(FeishuProperties.class)
@ImportRuntimeHints({FeishuRuntimeHints.class, LarkSdkRuntimeHints.class})
@Import({FeishuAutoConfiguration.Mongo.class, FeishuAutoConfiguration.Jdbc.class})
@ConditionalOnProperty(
    prefix = "app.feishu",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FeishuAutoConfiguration {

  /**
   * The Feishu message repository, registered for whichever backend is in play. Nested rather than
   * folded into the spring-agent-persistence-* modules because this repository belongs to this
   * module and must disappear with it when Feishu is switched off — which is why these are
   * {@code @Import}ed from the enclosing class rather than listed as auto-configurations of their
   * own: an entry in {@code AutoConfiguration.imports} is evaluated independently and would survive
   * {@code app.feishu.enabled=false}.
   *
   * <p>This module depends on neither backend, so on any given deployment one of these two classes
   * names a repository type that is not on the classpath. That is safe in this exact shape: both
   * are empty classes with no supertype and no member signatures, so they load; their
   * {@code @Enable…} annotation is then read reflectively and discarded when its type is absent, so
   * the repository interface it names — which really would fail to load — is never resolved. Adding
   * a field, a method or a supertype referring to either backend would break that, as would
   * replacing {@code @Import} with anything that resolves the nested class eagerly.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnPersistenceBackend(Type.MONGODB)
  @EnableMongoRepositories(basePackageClasses = MongoFeishuMessageRepo.class)
  public static class Mongo {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnPersistenceBackend(Type.JDBC)
  @EnableJpaRepositories(basePackageClasses = JpaFeishuMessageRepo.class)
  @EntityScan(basePackageClasses = FeishuMessage.class)
  public static class Jdbc {}

  @Bean
  @ConditionalOnMissingBean
  Client feishuClient(final FeishuProperties feishuProperties) {
    return new Client.Builder(feishuProperties.appId(), feishuProperties.appSecret()).build();
  }
}
