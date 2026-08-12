package me.kezhenxu94.springagent.integration.feishu.sheet;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ser.std.StdSerializer;

@Jacksonized
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValueRange(Range range, List<List<CellValue>> values) {
  @Builder
  @JsonSerialize(using = Range.Serializer.class)
  @JsonDeserialize(using = Range.Deserializer.class)
  public record Range(String sheetId, int rowStart, int rowEnd, int columnStart, int columnEnd) {

    @Override
    public String toString() {
      return String.format(
          "%s!%s%d:%s%d",
          sheetId(),
          numberToExcelColumn(columnStart()),
          rowStart(),
          numberToExcelColumn(columnEnd()),
          rowEnd());
    }

    static class Serializer extends StdSerializer<Range> {
      protected Serializer() {
        super(Range.class);
      }

      @Override
      public void serialize(Range value, JsonGenerator gen, SerializationContext provider) {
        gen.writeString(value.toString());
      }
    }

    static class Deserializer extends StdDeserializer<Range> {
      protected Deserializer() {
        super(Range.class);
      }

      @Override
      public Range deserialize(JsonParser p, DeserializationContext ctxt) {
        final var rangeStr = p.readValueAs(String.class);
        final var sheetId = rangeStr.split("!")[0];
        final var rowColStr = rangeStr.split("!")[1];
        final var startStr = rowColStr.split(":")[0];
        final var endStr = rowColStr.split(":")[1];
        final var pattern = Pattern.compile("(?<col>[A-Z]+)(?<row>[0-9]+)");
        final var startMatcher = pattern.matcher(startStr);
        if (!startMatcher.find()) {
          throw new IllegalArgumentException("invalid range: " + rangeStr);
        }
        final var rowStart = Integer.parseInt(startMatcher.group("row"));
        final var columnStart = excelColumnToNumber(startMatcher.group("col"));
        final var endMatcher = pattern.matcher(endStr);
        if (!endMatcher.find()) {
          throw new IllegalArgumentException("invalid range: " + rangeStr);
        }
        final var rowEnd = Integer.parseInt(endMatcher.group("row"));
        final var columnEnd = excelColumnToNumber(endMatcher.group("col"));
        return Range.builder()
            .sheetId(sheetId)
            .rowStart(rowStart)
            .rowEnd(rowEnd)
            .columnStart(columnStart)
            .columnEnd(columnEnd)
            .build();
      }
    }

    static String numberToExcelColumn(int columnNumber) {
      final var columnName = new StringBuilder();
      final var digits = (int) (Math.log(columnNumber) / Math.log(26)) + 1;

      for (int i = 0; i < digits; i++) {
        final var remainder = columnNumber % 26;
        if (remainder == 0) {
          columnName.append('Z');
          columnNumber = (columnNumber / 26) - 1;
        } else {
          columnName.append((char) ((remainder - 1) + 'A'));
          columnNumber /= 26;
        }
      }

      return columnName.reverse().toString();
    }

    static int excelColumnToNumber(String column) {
      var result = 0;
      for (int i = 0; i < column.length(); i++) {
        result = result * 26 + (column.charAt(i) - 'A' + 1);
      }
      return result;
    }
  }

  @Data
  @Accessors(fluent = true)
  @JsonDeserialize(using = CellValue.Deserializer.class)
  @JsonSerialize(using = CellValue.Serializer.class)
  public static class CellValue {
    final FormattedValues formattedValues;
    final String value;
    final String unformatted;

    public boolean isEmpty() {
      return Strings.isNullOrEmpty(value);
    }

    @Override
    public String toString() {
      return value;
    }

    @Slf4j
    static class Deserializer extends StdDeserializer<CellValue> {
      protected Deserializer() {
        super(CellValue.class);
      }

      @Override
      public CellValue deserialize(JsonParser p, DeserializationContext ctx) {
        final var node = ctx.readTree(p);
        if (node.isArray()) {
          try {
            final var vs = ctx.readTreeAsValue(node, FormattedValues.class);
            final var unformatted =
                vs.stream()
                    .map(FormattedValue::text)
                    .filter(not(Strings::isNullOrEmpty))
                    .collect(joining());
            vs.stream()
                .forEach(
                    v -> {
                      final var style = v.segmentStyle();
                      final var text = v.text();
                      if (style == null || Strings.isNullOrEmpty(style.foreColor())) {
                        return;
                      }
                      if (text.contains("<color") || text.contains("</color>")) {
                        return;
                      }
                      if ("#000000".equals(style.foreColor())) {
                        return;
                      }
                      v.text(
                          String.format(
                              "<color=%s>%s</color>", style.foreColor().toUpperCase(), text));
                      v.segmentStyle().foreColor("#73F545");
                      v.segmentStyle().bold(true);
                      return;
                    });
            final var value =
                vs.stream()
                    .map(FormattedValue::text)
                    .filter(not(Strings::isNullOrEmpty))
                    .collect(joining());
            return new CellValue(vs, value, unformatted);
          } catch (Exception e) {
            final var v = nodeToString(ctx, node);
            return new CellValue(new FormattedValues(), v, v);
          }
        }
        final var v = nodeToString(ctx, node);
        return new CellValue(new FormattedValues(), v, v);
      }

      private static String nodeToString(DeserializationContext ctx, JsonNode node) {
        if (node == null || node.isNull()) {
          return null;
        }
        if (node.isObject()) {
          final var textNode = node.get("text");
          if (textNode != null && textNode.isString()) {
            return textNode.asString();
          }
          log.warn("Unknown Feishu cell object shape, falling back to raw JSON: {}", node);
          return node.toString();
        }
        try {
          return ctx.readTreeAsValue(node, String.class);
        } catch (Exception e) {
          log.warn("Failed to coerce Feishu cell node to string, using raw form: {}", node);
          return node.asString(node.toString());
        }
      }
    }

    static class Serializer extends StdSerializer<CellValue> {
      protected Serializer() {
        super(CellValue.class);
      }

      @Override
      public void serialize(CellValue value, JsonGenerator gen, SerializationContext provider) {
        if (value.formattedValues() == null || value.formattedValues().isEmpty()) {
          gen.writeString(value.value);
          return;
        }
        gen.writeStartArray();
        for (var v : value.formattedValues()) {
          gen.writePOJO(v);
        }
        gen.writeEndArray();
      }
    }
  }

  public static class FormattedValues extends ArrayList<FormattedValue> {}

  @Data
  @Builder(toBuilder = true)
  @Jacksonized
  public static class FormattedValue {
    SegmentStyle segmentStyle;
    String text;
    String type;

    @Data
    @Builder(toBuilder = true)
    @Jacksonized
    public static class SegmentStyle {
      boolean bold;
      String foreColor;
    }
  }
}
