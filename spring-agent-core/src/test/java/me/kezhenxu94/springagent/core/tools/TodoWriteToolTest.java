package me.kezhenxu94.springagent.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.kezhenxu94.springagent.core.tools.TodoWriteTool.Todos;
import me.kezhenxu94.springagent.core.tools.TodoWriteTool.Todos.Status;
import me.kezhenxu94.springagent.core.tools.TodoWriteTool.Todos.TodoItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * Why this class is a fork is the first test here: the schema a model is shown.
 *
 * <p>The rest are upstream's own validation rules, kept because a fork that quietly loses one is a
 * fork nobody would notice going wrong — the model is simply never told it broke the rule.
 */
class TodoWriteToolTest {

  private final AtomicReference<Todos> handled = new AtomicReference<>();
  private final TodoWriteTool tool = TodoWriteTool.builder().todoEventHandler(handled::set).build();

  @Test
  @DisplayName("the schema asks for a plain array of items, which is what models send")
  void schemaIsFlat() throws Exception {
    // Spring AI keys the root schema by parameter name and inlines the parameter's own type. With
    // the parameter taking the Todos wrapper — whose single component is also called todos — the
    // only accepted payload was {"todos": {"todos": [...]}}, and a model sending the obvious
    // {"todos": [...]} got "cannot deserialize Todos from Array value" with nothing in the tool
    // description naming the fields to recover from. This is the whole reason for the fork.
    final var schema =
        JsonMapper.builder()
            .build()
            .readTree(
                JsonSchemaGenerator.generateForMethodInput(
                    TodoWriteTool.class.getDeclaredMethod("todoWrite", List.class)));

    final var todos = schema.path("properties").path("todos");
    assertThat(todos.path("type").asString()).isEqualTo("array");

    final var item = todos.path("items").path("properties");
    assertThat(item.size()).isEqualTo(3);
    assertThat(item.has("content")).isTrue();
    assertThat(item.has("status")).isTrue();
    assertThat(item.has("activeForm")).isTrue();
  }

  @Test
  @DisplayName("the handler is given the items the call carried")
  void handlerSeesTheItems() {
    final var items =
        List.of(
            new TodoItem("Read the config", Status.completed, "Reading the config"),
            new TodoItem("Fix the bug", Status.in_progress, "Fixing the bug"));

    assertThat(tool.todoWrite(items)).contains("successfully");
    assertThat(handled.get().todos()).isEqualTo(items);
  }

  @Test
  @DisplayName("an empty list is a list, and clears what a surface is showing")
  void emptyListIsAccepted() {
    assertThatCode(() -> tool.todoWrite(List.of())).doesNotThrowAnyException();
    assertThat(handled.get().todos()).isEmpty();
  }

  @Test
  @DisplayName("only one task may be in progress at a time")
  void oneInProgress() {
    assertThatThrownBy(
            () ->
                tool.todoWrite(
                    List.of(
                        new TodoItem("Read it", Status.in_progress, "Reading it"),
                        new TodoItem("Write it", Status.in_progress, "Writing it"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Only ONE task can be in_progress");
    assertThat(handled.get()).isNull();
  }

  @Test
  @DisplayName("an item missing any of its three fields is refused, and so is a missing item")
  void itemsAreValidated() {
    assertThatThrownBy(() -> tool.todoWrite(List.of(new TodoItem(" ", Status.pending, "Reading"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank content");
    assertThatThrownBy(() -> tool.todoWrite(List.of(new TodoItem("Read it", Status.pending, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank activeForm");
    assertThatThrownBy(() -> tool.todoWrite(List.of(new TodoItem("Read it", null, "Reading it"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null status");
    // A list literal cannot hold one, and a deserialized array can.
    assertThatThrownBy(() -> tool.todoWrite(Arrays.asList(new TodoItem[] {null})))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is null");
    assertThat(handled.get()).isNull();
  }

  @Test
  @DisplayName("no list at all is refused rather than read as an empty one")
  void nullListIsRefused() {
    // The flat signature is what makes this reachable: the model can now omit the property, where
    // before it would have sent a wrapper object. Clearing the list has to be said with [].
    assertThatThrownBy(() -> tool.todoWrite(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be null");
    assertThat(handled.get()).isNull();
  }
}
