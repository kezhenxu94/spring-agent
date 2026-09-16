package me.kezhenxu94.springagent.provider.anthropic.aot;

import com.anthropic.client.AnthropicClient;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import lombok.extern.slf4j.Slf4j;
import me.kezhenxu94.springagent.provider.anthropic.AnthropicProperties;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Reflection hints for the Anthropic SDK, which ships none of its own.
 *
 * <p>This is a third shape, and neither of the other two providers' would work here. {@code
 * OpenAiSdkRuntimeHints} reads {@code META-INF/native-image/reflect-config.json} out of openai-java
 * and upgrades its query-only entries; {@code GoogleGenAiRuntimeHints} registers almost nothing,
 * because google-genai ships invoke-capable entries and Spring AI ships hints of its own.
 * anthropic-java-core ships <b>no {@code META-INF/native-image} directory at all</b> — only a
 * ProGuard rules file, which GraalVM does not read — and {@code spring-ai-anthropic} ships no
 * {@code aot} package either. So a copy of the OpenAI class would find no file, register nothing,
 * and say nothing about it.
 *
 * <p>Instead the SDK's own jar is enumerated at build time and the packages this project actually
 * reaches are registered invoke-capable. Enumeration rather than a list, because the alternative is
 * a few thousand class names in a Java file that nobody could keep current.
 *
 * <p><b>The allow-list is narrow on purpose.</b> The jar holds over eight thousand classes, most of
 * them the beta and batch APIs this project never calls. Registering all of them would put
 * Anthropic's entire API surface into every binary — the weight {@code OpenAiSdkRuntimeHints}'
 * comment warns about — so only the request/response types for message sending, the JSON machinery
 * they deserialize through, and the errors a rejection is built from are taken.
 *
 * <p>Every failure is empty rather than fatal, for the reason the OpenAI class gives: a build
 * without the SDK on the classpath is a legitimate build, and a hints registrar is not the place to
 * fail one.
 *
 * <p>One caveat this class cannot fix, recorded here and in the module README rather than left to
 * be discovered: the SDK is Kotlin, and Kotlin reflection in a native image also needs {@code
 * kotlin.Metadata} retained, which comes from the GraalVM reachability-metadata repository rather
 * than from here. <b>A native image of an Anthropic-backed deployment is unverified.</b>
 */
@Slf4j
public class AnthropicRuntimeHints implements RuntimeHintsRegistrar {

  /**
   * The packages a run actually reaches. {@code models.messages} is the API this project calls;
   * {@code core} is the {@code JsonValue}/{@code JsonField} machinery everything deserializes
   * through; {@code errors} is what {@code AnthropicProviderRejection} reads. Deliberately not
   * {@code models.beta}, {@code models.batches} or the rest of {@code models}.
   */
  private static final List<String> PACKAGES =
      List.of("com.anthropic.core.", "com.anthropic.models.messages.", "com.anthropic.errors.");

  private static final MemberCategory[] INVOKE = {
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS
  };

  @Override
  public void registerHints(final RuntimeHints hints, final ClassLoader classLoader) {
    // This module's own bound records, which nobody else states.
    hints
        .reflection()
        .registerType(TypeReference.of(AnthropicProperties.class), INVOKE)
        .registerType(TypeReference.of(AnthropicProperties.Vertex.class), INVOKE);

    final var types = sdkTypes();
    for (final var type : types) {
      hints.reflection().registerTypeIfPresent(classLoader, type, INVOKE);
    }
    log.debug("Registered {} Anthropic SDK types for reflection", types.size());
  }

  /**
   * Every class under {@link #PACKAGES} in the jar {@link AnthropicClient} came from, inner classes
   * included — the {@code $Builder} and {@code $Companion} types the SDK constructs everything
   * through, and which a package walk picks up for free because they are separate entries.
   */
  private static List<String> sdkTypes() {
    final var source = AnthropicClient.class.getProtectionDomain().getCodeSource();
    if (source == null || source.getLocation() == null) {
      return List.of();
    }
    final var names = new ArrayList<String>();
    try (final var jar = new JarFile(new File(source.getLocation().toURI()))) {
      jar.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class"))
          .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
          .filter(name -> PACKAGES.stream().anyMatch(name::startsWith))
          .forEach(names::add);
    } catch (final Exception e) {
      // Not fatal, and not even a warning: a build where the SDK is a directory rather than a jar,
      // or absent entirely, is a build that simply needs no hints from here.
      log.debug("Could not enumerate the Anthropic SDK jar; registering no SDK hints", e);
      return List.of();
    }
    return names;
  }
}
