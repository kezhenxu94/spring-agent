package me.kezhenxu94.springagent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.docx.v1.model.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FeishuDocxService passes block payloads through as raw JSON, relying on the Lark SDK's own Gson
 * instance ({@code Jsons.DEFAULT}) rather than Jackson to (de)serialize {@link Block}, since {@link
 * Block} is annotated with Gson's {@code @SerializedName}, not Jackson annotations. This test
 * guards against that snake_case mapping silently breaking.
 */
class FeishuDocBlockJsonRoundTripTest {

  @Test
  @DisplayName("Jsons.DEFAULT round-trips a text block's snake_case fields through Block[]")
  void roundTripsTextBlock() {
    final var json =
        """
        [
          {
            "block_id": "tempTextBlock1",
            "block_type": 2,
            "text": {
              "elements": [
                {
                  "text_run": {
                    "content": "hello world",
                    "text_element_style": {
                      "bold": true
                    }
                  }
                }
              ]
            }
          }
        ]
        """;

    final var blocks = Jsons.DEFAULT.fromJson(json, Block[].class);

    assertThat(blocks).hasSize(1);
    assertThat(blocks[0].getBlockId()).isEqualTo("tempTextBlock1");
    assertThat(blocks[0].getBlockType()).isEqualTo(2);
    assertThat(blocks[0].getText().getElements()).hasSize(1);
    assertThat(blocks[0].getText().getElements()[0].getTextRun().getContent())
        .isEqualTo("hello world");
    assertThat(blocks[0].getText().getElements()[0].getTextRun().getTextElementStyle().getBold())
        .isTrue();

    final var reserialized = Jsons.DEFAULT.toJson(blocks);
    assertThat(reserialized).contains("\"block_id\"", "\"block_type\"", "\"text_run\"");
  }

  @Test
  @DisplayName("Jsons.DEFAULT round-trips a nested block tree with children references")
  void roundTripsNestedChildren() {
    final var json =
        """
        [
          {
            "block_id": "parent",
            "block_type": 24,
            "children": ["child1", "child2"]
          },
          {
            "block_id": "child1",
            "block_type": 25
          },
          {
            "block_id": "child2",
            "block_type": 25
          }
        ]
        """;

    final var blocks = Jsons.DEFAULT.fromJson(json, Block[].class);

    assertThat(blocks).hasSize(3);
    assertThat(blocks[0].getChildren()).containsExactly("child1", "child2");
  }
}
