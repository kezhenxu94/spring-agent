package me.kezhenxu94.springagent.core.tools.i18n;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * What a module actually offers the model, worked out by reflection, so that a test can check every
 * translation still names something real.
 *
 * <p>The tools a translation may name are found by their {@code @Tool} methods rather than by how
 * they come to be beans: this repository registers some by annotating the class and at least one by
 * annotating a {@code @Bean} method, and a check on translations should not care which.
 *
 * <p>Parameter names come from {@code Parameter::getName}, which is sound because {@code
 * -parameters} is on for every module — and it has to be, since that is also where the input
 * schema's property names come from.
 */
final class ToolTextsInventory {

  private ToolTextsInventory() {}

  /** Every tool a module offers, and the parameters each one takes. */
  static Map<String, Set<String>> toolsOf(final String basePackage, final List<Class<?>> extraTypes)
      throws Exception {
    final var types = new ArrayList<Class<?>>(extraTypes);
    final var resolver = new PathMatchingResourcePatternResolver();
    final var readers = new CachingMetadataReaderFactory(resolver);
    final var pattern =
        "classpath*:" + ClassUtils.convertClassNameToResourcePath(basePackage) + "/**/*.class";
    for (final var resource : resolver.getResources(pattern)) {
      final var className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
      try {
        types.add(ClassUtils.forName(className, ToolTextsInventory.class.getClassLoader()));
      } catch (Throwable ignored) {
        // A class whose own dependencies are absent cannot be a tool this module offers.
      }
    }

    final var tools = new HashMap<String, Set<String>>();
    for (final var type : types) {
      for (final var method : type.getDeclaredMethods()) {
        final var tool = method.getAnnotation(Tool.class);
        if (tool == null) {
          continue;
        }
        final var name = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
        final var parameters =
            java.util.Arrays.stream(method.getParameters())
                // Exactly the exclusion the schema generator makes.
                .filter(parameter -> !ToolContext.class.isAssignableFrom(parameter.getType()))
                .map(java.lang.reflect.Parameter::getName)
                .collect(Collectors.toUnmodifiableSet());
        tools.merge(
            name,
            parameters,
            (first, second) ->
                Set.copyOf(
                    java.util.stream.Stream.concat(first.stream(), second.stream()).toList()));
      }
    }
    return tools;
  }

  /**
   * The English a tool declares: its own description, and one per parameter.
   *
   * <p>Read by reflection from the annotations themselves, which is what lets the completeness
   * check be a unit test rather than something only a running agent could answer.
   *
   * @param description what {@code @Tool} says
   * @param parameters what each {@code @ToolParam} says, by parameter name
   */
  record English(String description, Map<String, String> parameters) {}

  /** What every tool of a module declares in English, by tool name. */
  static Map<String, English> englishOf(final String basePackage, final List<Class<?>> extraTypes)
      throws Exception {
    final var types = new ArrayList<Class<?>>(extraTypes);
    final var resolver = new PathMatchingResourcePatternResolver();
    final var readers = new CachingMetadataReaderFactory(resolver);
    final var pattern =
        "classpath*:" + ClassUtils.convertClassNameToResourcePath(basePackage) + "/**/*.class";
    for (final var resource : resolver.getResources(pattern)) {
      final var className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
      try {
        types.add(ClassUtils.forName(className, ToolTextsInventory.class.getClassLoader()));
      } catch (Throwable ignored) {
        // Not a tool this module offers.
      }
    }

    final var english = new HashMap<String, English>();
    for (final var type : types) {
      for (final var method : type.getDeclaredMethods()) {
        final var tool = method.getAnnotation(Tool.class);
        if (tool == null) {
          continue;
        }
        final var name = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
        final var parameters = new HashMap<String, String>();
        for (final var parameter : method.getParameters()) {
          final var described = parameter.getAnnotation(ToolParam.class);
          if (described != null && StringUtils.hasText(described.description())) {
            parameters.put(parameter.getName(), described.description());
          }
        }
        english.merge(
            name,
            new English(tool.description(), parameters),
            (first, second) -> {
              final var merged = new HashMap<>(first.parameters());
              merged.putAll(second.parameters());
              return new English(first.description(), merged);
            });
      }
    }
    return english;
  }

  /** Every key in every locale variant of a module's parameter bundle. */
  static Set<String> parameterKeys(final String bundleBase) throws Exception {
    final var keys = new java.util.HashSet<String>();
    final var resolver = new PathMatchingResourcePatternResolver();
    for (final var resource : resolver.getResources("classpath*:" + bundleBase + "*.properties")) {
      final var properties = new Properties();
      try (var stream = resource.getInputStream()) {
        properties.load(
            new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
      }
      keys.addAll(properties.stringPropertyNames());
    }
    return keys;
  }

  /** Every description file a module ships, as the tool name and locale suffix each one carries. */
  static List<DescriptionFile> descriptionFiles(final String promptDirectory) throws Exception {
    final var files = new ArrayList<DescriptionFile>();
    final var resolver = new PathMatchingResourcePatternResolver();
    for (final var resource : resolver.getResources("classpath*:" + promptDirectory + "*.md")) {
      final var filename = resource.getFilename();
      if (filename == null) {
        continue;
      }
      final var base = filename.substring(0, filename.length() - ".md".length());
      // A locale suffix is _lang or _lang_COUNTRY, and a tool name never contains an underscore.
      final var underscore = base.indexOf('_');
      files.add(
          underscore < 0
              ? new DescriptionFile(filename, base, null)
              : new DescriptionFile(
                  filename, base.substring(0, underscore), base.substring(underscore + 1)));
    }
    return files;
  }

  /**
   * @param filename what the file is called, for a message a reader can act on
   * @param toolName the tool it claims to describe
   * @param localeSuffix the locale it claims to be written in, or null for the base file
   */
  record DescriptionFile(String filename, String toolName, String localeSuffix) {
    Locale locale() {
      return localeSuffix == null ? null : Locale.forLanguageTag(localeSuffix.replace('_', '-'));
    }
  }
}
