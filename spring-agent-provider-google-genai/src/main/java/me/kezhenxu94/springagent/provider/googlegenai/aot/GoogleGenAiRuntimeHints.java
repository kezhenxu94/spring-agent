package me.kezhenxu94.springagent.provider.googlegenai.aot;

import me.kezhenxu94.springagent.provider.googlegenai.GoogleGenAiProperties;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * What this module needs at runtime that a native image would otherwise discard, which is much less
 * than its sibling needs — and the difference is worth writing down, because the obvious thing to
 * do here is copy {@code OpenAiSdkRuntimeHints}, and that would be a file that does nothing.
 *
 * <p>That class exists because openai-java ships around twelve thousand <em>query-only</em> entries
 * in its own {@code reflect-config.json}: enough for a native image to describe the types and not
 * enough to construct them, so deserialization compiles, builds, and then fails at run time. It has
 * to read the SDK's own configuration back and upgrade it.
 *
 * <p>The {@code google-genai} SDK ships its configuration already invoke-capable — its {@code
 * auto-value/reflect-config.json} sets {@code allDeclaredConstructors} and {@code
 * allDeclaredMethods} on every generated type, which are the ones Jackson actually builds —
 * alongside proxy, resource, JNI and serialization configs. GraalVM reads all of that out of the
 * jar itself. Spring AI covers its own model classes in {@code
 * org.springframework.ai.google.genai.aot.GoogleGenAiRuntimeHints}. So neither needs help from
 * here, and adding some would be a second, staler copy of what the SDK already states.
 *
 * <p>What is genuinely this module's own is the configuration it binds: a record is bound through
 * its canonical constructor, and a nested one reached only from another record is easy for AOT to
 * miss. Registering them costs nothing and is the one thing nobody else has said.
 */
public class GoogleGenAiRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    for (final var type :
        new Class<?>[] {
          GoogleGenAiProperties.class,
          GoogleGenAiProperties.Chat.class,
          GoogleGenAiProperties.Embedding.class,
          GoogleGenAiProperties.Embedding.Text.class,
          GoogleGenAiProperties.Image.class
        }) {
      hints
          .reflection()
          .registerType(
              type,
              MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
              MemberCategory.INVOKE_DECLARED_METHODS);
    }
  }
}
