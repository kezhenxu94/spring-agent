package me.kezhenxu94.springagent.integration.websocket.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.kezhenxu94.springagent.core.dao.models.ScheduledTask;
import me.kezhenxu94.springagent.core.dao.repo.ScheduledTaskRepo;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskEdit;
import me.kezhenxu94.springagent.core.scheduling.ScheduledTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

/**
 * How a JSON body becomes a {@link ScheduledTaskEdit}. What an edit may then say is {@code
 * ScheduledTaskEditTest}'s; the only thing asserted here is the translation, which is the half a
 * browser can get wrong by leaving something out.
 */
class TaskControllerTest {

  private static final String ME = "ou_me";

  private final ScheduledTask task =
      ScheduledTask.builder()
          .id("t1")
          .userId(ME)
          .title("Thread digest")
          .taskText("summarise the thread")
          .cronExpression("0 0 9 * * MON")
          .maxRuns(10)
          .runCount(3)
          .status(ScheduledTask.Status.ACTIVE)
          .build();

  @Test
  @DisplayName("an edit that names no firing count leaves the ceiling alone")
  void anAbsentMaxRunsIsNotAnUnboxedNull() {
    // The bug this exists for: UNLIMITED is an int, so the conditional that chose between it and
    // the body's value was promoted to int and unboxed a null — and every edit the page sends
    // omits maxRuns unless somebody actually moved it, so renaming a task answered 500.
    final var edit = editWith(Map.of("title", "Morning digest"));

    assertThat(edit.maxRuns()).isNull();
    assertThat(edit.title()).isEqualTo("Morning digest");
  }

  @Test
  @DisplayName("a firing count cleared to nothing asks for the ceiling to go, not to be kept")
  void anExplicitNullMaxRunsIsTheSentinel() {
    // Present-but-null is the one case that has to be told from an absent key, and it is the arm
    // of that conditional the type of the whole expression came from.
    final var body = new HashMap<String, Object>();
    body.put("maxRuns", null);

    assertThat(editWith(body).maxRuns()).isEqualTo(ScheduledTaskEdit.UNLIMITED);
  }

  @Test
  @DisplayName("an expiry cleared to nothing does the same for the expiry")
  void anExplicitNullExpiryIsTheSentinel() {
    final var body = new HashMap<String, Object>();
    body.put("expiresAt", null);

    final var edit = editWith(body);
    assertThat(edit.expiresAt()).isEqualTo(ScheduledTaskEdit.NEVER);
    // And nothing else was asked for by a body that named nothing else.
    assertThat(edit.maxRuns()).isNull();
    assertThat(edit.title()).isNull();
  }

  /** What the controller made of that body, as handed to the service. */
  private ScheduledTaskEdit editWith(final Map<String, Object> body) {
    final var tasks = mock(ScheduledTaskRepo.class);
    when(tasks.findById(eq("t1"))).thenReturn(Optional.of(task));
    final var schedules = mock(ScheduledTaskService.class);
    when(schedules.edit(any(), any()))
        .thenReturn(new ScheduledTaskEdit.Result(task, List.of("changed"), ""));

    new TaskController(tasks, schedules).edit(user(), "t1", body);

    final var captor = ArgumentCaptor.forClass(ScheduledTaskEdit.class);
    org.mockito.Mockito.verify(schedules).edit(eq(task), captor.capture());
    return captor.getValue();
  }

  private static OAuth2User user() {
    final Map<String, Object> attributes = Map.of("open_id", ME, "name", "Me");
    return new DefaultOAuth2User(List.of(new OAuth2UserAuthority(attributes)), attributes, "name");
  }
}
