package me.kezhenxu94.springagent.core.aot;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the OpenAI SDK's model classes invocable in a native image, not merely visible.
 *
 * <p>openai-java ships its own {@code META-INF/native-image/reflect-config.json}, twelve thousand
 * entries of it, and every one declares {@code queryAllDeclaredMethods} — enough to reflect over a
 * class and see what it has, not enough to call any of it. The SDK deserializes by calling a
 * private {@code putAdditionalProperty} on each model, so the first embedding of the first turn
 * fails with
 *
 * <pre>MissingReflectionRegistrationError: Cannot reflectively invoke method 'private final void
 * com.openai.models.embeddings.CreateEmbeddingResponse.putAdditionalProperty'</pre>
 *
 * <p>Which is worse than it sounds: the error surfaces on a reactor scheduler thread as a fatal
 * exception, so nothing reports the run as finished and a command line waiting on it simply hangs.
 *
 * <p>Rather than name the classes — they are generated, there are thousands, and the next model
 * added would need another one — this reads the SDK's own list at build time and upgrades the
 * entries under {@link #PACKAGES} to invocable. Those are the APIs this project calls; registering
 * all twelve thousand would carry the whole of OpenAI's API surface into every binary.
 */
public class OpenAiSdkRuntimeHints implements RuntimeHintsRegistrar {

  /** Where the SDK publishes the list, at the root of {@code openai-java-core}. */
  private static final String CONFIG = "META-INF/native-image/reflect-config.json";

  /**
   * The SDK packages this project reaches: chat completions, embeddings and audio transcription,
   * plus the {@code com.openai.core} machinery all three deserialize through.
   */
  private static final List<String> PACKAGES =
      List.of(
          "com.openai.core",
          "com.openai.models.chat",
          "com.openai.models.completions",
          "com.openai.models.embeddings",
          "com.openai.models.audio");

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final var name : declaredTypes(classLoader)) {
      hints
          .reflection()
          .registerTypeIfPresent(
              classLoader,
              name,
              // Only the invoke categories: the SDK's own file already covers querying and fields,
              // and this is exactly what it leaves out.
              MemberCategory.INVOKE_DECLARED_METHODS,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
  }

  /** The names under {@link #PACKAGES} the SDK declared, across every copy of the file found. */
  private static List<String> declaredTypes(final ClassLoader classLoader) {
    final var mapper = JsonMapper.builder().build();
    final var names = new java.util.LinkedHashSet<String>();
    try {
      final var resources = classLoader.getResources(CONFIG);
      while (resources.hasMoreElements()) {
        try (var stream = resources.nextElement().openStream()) {
          final List<Map<String, Object>> entries = mapper.readValue(stream, List.class);
          for (final var entry : entries) {
            if (entry.get("name") instanceof String name
                && PACKAGES.stream().anyMatch(name::startsWith)) {
              names.add(name);
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // Deliberately not fatal. A build without the SDK on its classpath is a legitimate one, and
      // a build whose file this cannot read should fail at run time with the specific missing
      // registration rather than here with a parse error nobody can act on.
      return List.of();
    }
    return List.copyOf(names);
  }
}
