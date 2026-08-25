package me.kezhenxu94.springagent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.context.annotation.Bean;

/**
 * That the interceptor is contributed as a bean, and reaches the OkHttp client when it is.
 *
 * <p>Worth testing separately from what the interceptor does, because the failure mode here is
 * silence. Spring AI collects these customizers through an {@code ObjectProvider}: a bean that
 * stops being defined — a renamed condition, a method that loses its {@code @Bean} — raises nothing
 * anywhere. Runs simply go back to failing for unknowable reasons, which is the state this whole
 * change exists to end.
 */
class OpenAiErrorBodyCustomizerWiringTest {

  @Test
  @DisplayName("the auto-configuration declares the customizer as a bean")
  void theAutoConfigurationDeclaresTheCustomizerAsABean() throws Exception {
    final var method = customizerBeanMethod();

    assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
    assertThat(method.getReturnType()).isEqualTo(OpenAiHttpClientBuilderCustomizer.class);
  }

  @Test
  @DisplayName("the customizer it returns puts the interceptor on the built OkHttp client")
  void theCustomizerPutsTheInterceptorOnTheClient() throws Exception {
    final var method = customizerBeanMethod();
    method.setAccessible(true);
    final var customizer =
        (OpenAiHttpClientBuilderCustomizer)
            method.invoke(newInstanceOf(SpringAgentCoreAutoConfiguration.class));

    final var builder = SpringAiOpenAiHttpClient.builder();
    customizer.customize(builder);

    // Through the real builder, so this covers the seam and not just the lambda: whatever
    // interceptor() does with what it is handed, the interceptor has to end up on the client that
    // actually makes the call.
    assertThat(builder.build().getOkHttpClient().interceptors())
        .hasAtLeastOneElementOfType(OpenAiErrorBodyLoggingInterceptor.class);
  }

  private static Method customizerBeanMethod() throws NoSuchMethodException {
    return SpringAgentCoreAutoConfiguration.class.getDeclaredMethod(
        "openAiErrorBodyLoggingCustomizer");
  }

  /**
   * The auto-configuration without running it. The bean method under test takes no collaborators,
   * so an instance with null dependencies is enough and avoids standing up a context that would
   * want a model endpoint and a database.
   */
  private static Object newInstanceOf(final Class<?> type) throws Exception {
    final var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(new Object[constructor.getParameterCount()]);
  }
}
