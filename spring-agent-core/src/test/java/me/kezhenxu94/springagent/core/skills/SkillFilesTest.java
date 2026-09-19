package me.kezhenxu94.springagent.core.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import me.kezhenxu94.springagent.core.tools.HomeDir;
import me.kezhenxu94.springagent.core.tools.UserHome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What may be reached, and what a skill looks like from outside.
 *
 * <p>The guard cases come first and are the reason this class exists. A skills folder is code the
 * agent will later load and run, so a path that leaves it is not a display bug — it is a write into
 * somebody else's instructions. {@code UploadPathTest} in the browser module makes the same
 * argument about uploads and its list of strings is where these start.
 */
class SkillFilesTest {

  @TempDir Path location;

  SkillFiles skills;
  HomeDir home;

  @BeforeEach
  void setUp() throws Exception {
    skills = new SkillFiles();
    home = new UserHome(location.resolve("ou_1"));
    Files.createDirectories(location.resolve("ou_1/skills"));
    Files.createDirectories(location.resolve("ou_1/memories"));
  }

  private Path skill(final String name, final String manifest) throws Exception {
    final var dir = location.resolve("ou_1/skills").resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("SKILL.md"), manifest);
    return dir;
  }

  @Nested
  @DisplayName("the guard")
  class Guard {

    @Test
    @DisplayName("a path climbing out of the skills directory is refused")
    void refusesClimbingOut() {
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(
              () -> skills.guarded(home, location.resolve("ou_1/skills/../memories/MEMORY.md")));
    }

    @Test
    @DisplayName("a path in another part of the same home is refused")
    void refusesTheRestOfTheHome() {
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.guarded(home, location.resolve("ou_1/memories/MEMORY.md")));
    }

    @Test
    @DisplayName("an absolute path somewhere else entirely is refused")
    void refusesAnywhereElse() {
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.guarded(home, Path.of("/etc/passwd")));
    }

    @Test
    @DisplayName("a skill name carrying a separator is refused before it is resolved")
    void refusesASkillNameWithASeparator() {
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.locate(home, "../memories"));
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.target(home, "a/b"));
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.target(home, ".."));
    }

    @Test
    @DisplayName("a file path climbing out of its skill is refused")
    void refusesAFilePathClimbingOut() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.read(home, "greeting", "../../memories/MEMORY.md"));
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.write(home, dir, "../../../escaped.md", "no"));
      assertThat(location.resolve("escaped.md")).doesNotExist();
    }

    @Test
    @DisplayName("a symlink out of the skills directory is refused, though its path looks inside")
    void refusesASymlinkOut() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      final var secret = location.resolve("secret.txt");
      Files.writeString(secret, "not yours");
      // The path below never leaves the skills folder by name — normalizing it answers nothing.
      // Only following the link does, which is the whole reason the second check is there.
      Files.createSymbolicLink(dir.resolve("out.txt"), secret);

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.read(home, "greeting", "out.txt"));
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.write(home, dir, "out.txt", "overwritten"));
      assertThat(secret).hasContent("not yours");
    }

    @Test
    @DisplayName("a symlinked directory inside a skill is refused for everything under it")
    void refusesASymlinkedDirectory() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      final var elsewhere = location.resolve("elsewhere");
      Files.createDirectories(elsewhere);
      Files.createSymbolicLink(dir.resolve("refs"), elsewhere);

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.write(home, dir, "refs/planted.md", "no"));
      assertThat(elsewhere.resolve("planted.md")).doesNotExist();
    }

    @Test
    @DisplayName("an ordinary path is allowed, even where the storage root is itself a symlink")
    void allowsAnOrdinaryPath() throws Exception {
      // macOS puts a temporary directory under /var, which is a link to /private/var. Comparing a
      // resolved real path against a declared one refuses every write on such a host.
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");

      assertThatNoException()
          .isThrownBy(() -> skills.write(home, dir, "references/notes.md", "kept"));
      assertThat(dir.resolve("references/notes.md")).hasContent("kept");
    }
  }

  @Nested
  @DisplayName("what a skill is")
  class WhatASkillIs {

    @Test
    @DisplayName("a folder with no SKILL.md is not a skill")
    void needsAManifest() throws Exception {
      Files.createDirectories(location.resolve("ou_1/skills/not-a-skill"));
      Files.writeString(location.resolve("ou_1/skills/not-a-skill/README.md"), "hello");

      assertThat(skills.list(home)).isEmpty();
      assertThat(skills.locate(home, "not-a-skill")).isEmpty();
      assertThat(skills.detail(home, "not-a-skill")).isEmpty();
    }

    @Test
    @DisplayName("front matter names and describes it, and the folder is still its identity")
    void readsFrontMatter() throws Exception {
      skill("pdf-filler", "---\nname: fill-a-pdf\ndescription: Fills a form.\n---\nSteps.");

      assertThat(skills.list(home))
          .singleElement()
          .satisfies(
              summary -> {
                assertThat(summary.name()).isEqualTo("pdf-filler");
                assertThat(summary.declaredName()).isEqualTo("fill-a-pdf");
                assertThat(summary.description()).isEqualTo("Fills a form.");
                assertThat(summary.fileCount()).isEqualTo(1);
              });
    }

    @Test
    @DisplayName("a skill whose front matter names nothing is still listed, under its folder name")
    void listsASkillWithNoDeclaredName() throws Exception {
      // SkillsTool skips this one silently, so it is offered to nobody. The page is where that has
      // to become visible, which it cannot be if the list drops it too.
      skill("nameless", "no front matter at all");

      assertThat(skills.list(home))
          .singleElement()
          .satisfies(
              summary -> {
                assertThat(summary.name()).isEqualTo("nameless");
                assertThat(summary.declaredName()).isEmpty();
              });
    }

    @Test
    @DisplayName("writing a SKILL.md that names something else does not rename the folder")
    void doesNotRenameTheFolder() throws Exception {
      final var dir = skill("pdf-filler", "---\nname: pdf-filler\n---\nSteps.");

      skills.write(home, dir, "SKILL.md", "---\nname: something-else\n---\nSteps.");

      assertThat(skills.list(home))
          .singleElement()
          .satisfies(
              summary -> {
                assertThat(summary.name()).isEqualTo("pdf-filler");
                assertThat(summary.declaredName()).isEqualTo("something-else");
              });
      assertThat(location.resolve("ou_1/skills/something-else")).doesNotExist();
    }

    @Test
    @DisplayName("a skill in a nearer scope hides one of the same name further out")
    void nearestScopeWins() throws Exception {
      final var composite =
          new me.kezhenxu94.springagent.core.tools.CompositeHomeDir(
              new UserHome(location.resolve("ou_1")),
              java.util.List.of(new UserHome(location.resolve("tenant/t_1"))));
      skill("greeting", "---\nname: greeting\ndescription: mine\n---\nhi");
      Files.createDirectories(location.resolve("tenant/t_1/skills/greeting"));
      Files.writeString(
          location.resolve("tenant/t_1/skills/greeting/SKILL.md"),
          "---\nname: greeting\ndescription: theirs\n---\nhi");

      assertThat(skills.list(composite))
          .singleElement()
          .satisfies(summary -> assertThat(summary.description()).isEqualTo("mine"));
    }
  }

  @Nested
  @DisplayName("reading a file")
  class Reading {

    @Test
    @DisplayName("text comes back as text, with the path the wire and the page use")
    void readsText() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "references/notes.md", "# Notes\n");

      assertThat(skills.read(home, "greeting", "references/notes.md"))
          .hasValueSatisfying(
              file -> {
                assertThat(file.text()).isEqualTo("# Notes\n");
                assertThat(file.entry().path()).isEqualTo("references/notes.md");
                assertThat(file.binary()).isFalse();
                assertThat(file.tooLarge()).isFalse();
              });
    }

    @Test
    @DisplayName("a file that is not text says so rather than coming back as mojibake")
    void refusesToPretendBinaryIsText() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      Files.write(dir.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3});

      assertThat(skills.read(home, "greeting", "logo.png"))
          .hasValueSatisfying(
              file -> {
                assertThat(file.binary()).isTrue();
                assertThat(file.text()).isNull();
              });
    }

    @Test
    @DisplayName("a file over the cap says so, and its size still comes back")
    void refusesToCarryAHugeFile() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      final var big = new byte[(int) SkillFiles.MAX_TEXT_BYTES + 1];
      java.util.Arrays.fill(big, (byte) 'a');
      Files.write(dir.resolve("big.txt"), big);

      assertThat(skills.read(home, "greeting", "big.txt"))
          .hasValueSatisfying(
              file -> {
                assertThat(file.tooLarge()).isTrue();
                assertThat(file.text()).isNull();
                assertThat(file.entry().size()).isEqualTo(SkillFiles.MAX_TEXT_BYTES + 1);
              });
    }

    @Test
    @DisplayName("a file that was deleted reads as absent rather than as a refusal")
    void absentIsNotDenied() throws Exception {
      skill("greeting", "---\nname: greeting\n---\nhi");

      assertThat(skills.read(home, "greeting", "gone.md")).isEmpty();
      assertThat(skills.read(home, "no-such-skill", "SKILL.md")).isEmpty();
    }
  }

  @Nested
  @DisplayName("the tree")
  class Tree {

    @Test
    @DisplayName("folders come before files at each level, and every path is /-separated")
    void ordersTheTree() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "LICENSE.txt", "x");
      skills.write(home, dir, "references/b.md", "x");
      skills.write(home, dir, "references/a.md", "x");

      assertThat(skills.detail(home, "greeting"))
          .hasValueSatisfying(
              detail ->
                  assertThat(detail.entries().stream().map(SkillEntry::path))
                      .containsExactly(
                          "references",
                          "references/a.md",
                          "references/b.md",
                          "LICENSE.txt",
                          "SKILL.md"));
    }
  }

  @Nested
  @DisplayName("deleting")
  class Deleting {

    @Test
    @DisplayName("a whole skill goes, and only that skill")
    void deletesASkill() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "references/notes.md", "x");
      skill("other", "---\nname: other\n---\nhi");

      skills.deleteSkill(home, dir);

      assertThat(dir).doesNotExist();
      assertThat(location.resolve("ou_1/skills/other")).exists();
    }

    @Test
    @DisplayName("a folder that is not a skill is refused, because the delete is recursive")
    void refusesToDeleteWhatIsNotASkill() throws Exception {
      final var stray = location.resolve("ou_1/skills/stray");
      Files.createDirectories(stray);
      Files.writeString(stray.resolve("README.md"), "hello");

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.deleteSkill(home, stray));
      assertThat(stray).exists();
    }

    @Test
    @DisplayName("one file goes, and deleting it twice is not an error the second time")
    void deletesAFile() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "notes.md", "x");

      assertThat(skills.deleteFile(home, dir, "notes.md")).isTrue();
      assertThat(skills.deleteFile(home, dir, "notes.md")).isFalse();
    }

    @Test
    @DisplayName("SKILL.md cannot be deleted as a file, or the skill would become unreachable")
    void refusesToDeleteTheManifest() throws Exception {
      // Without it, locate() stops finding the folder — so it leaves the list, and every endpoint
      // that names a skill stops reaching it, including the one that would have deleted it.
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.deleteFile(home, dir, "SKILL.md"));
      assertThat(dir.resolve("SKILL.md")).exists();
      assertThat(skills.locate(home, "greeting")).isPresent();
    }

    @Test
    @DisplayName("a file called SKILL.md deeper in the skill is an ordinary file")
    void onlyTheTopLevelManifestIsProtected() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "examples/SKILL.md", "an example of one");

      assertThat(skills.deleteFile(home, dir, "examples/SKILL.md")).isTrue();
    }

    @Test
    @DisplayName("a folder is not deleted by the endpoint that deletes a file")
    void refusesToDeleteAFolderAsAFile() throws Exception {
      final var dir = skill("greeting", "---\nname: greeting\n---\nhi");
      skills.write(home, dir, "references/notes.md", "x");

      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.deleteFile(home, dir, "references"));
      assertThat(dir.resolve("references/notes.md")).exists();
    }
  }

  @Nested
  @DisplayName("importing a zip")
  class Importing {

    private byte[] zip(final Map<String, byte[]> entries) throws Exception {
      final var out = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(out)) {
        for (final var entry : entries.entrySet()) {
          zip.putNextEntry(new ZipEntry(entry.getKey()));
          zip.write(entry.getValue());
          zip.closeEntry();
        }
      }
      return out.toByteArray();
    }

    private byte[] zipOf(final String... pathsAndText) throws Exception {
      final var entries = new LinkedHashMap<String, byte[]>();
      for (var i = 0; i < pathsAndText.length; i += 2) {
        entries.put(pathsAndText[i], pathsAndText[i + 1].getBytes(StandardCharsets.UTF_8));
      }
      return zip(entries);
    }

    @Test
    @DisplayName("an archive becomes the skill's files")
    void unpacksAnArchive() throws Exception {
      final var bytes =
          zipOf(
              "SKILL.md", "---\nname: imported\n---\nhi",
              "references/notes.md", "kept");

      final var files = skills.unpack(new ByteArrayInputStream(bytes));

      assertThat(files.keySet()).containsExactly("SKILL.md", "references/notes.md");
      assertThat(new String(files.get("references/notes.md"), StandardCharsets.UTF_8))
          .isEqualTo("kept");
    }

    @Test
    @DisplayName("an entry that climbs out of the skill is refused, and nothing is unpacked")
    void refusesZipSlip() throws Exception {
      // The oldest thing in the book, and an archive is the one input here whose file names are
      // written by whoever made it rather than by whoever uploads it.
      for (final var attempt :
          List.of("../../../.ssh/authorized_keys", "../escaped.md", "a/../../out.md")) {
        final var bytes = zipOf("SKILL.md", "---\nname: x\n---\n", attempt, "no");
        assertThatExceptionOfType(SkillAccessDenied.class)
            .as("%s", attempt)
            .isThrownBy(() -> skills.unpack(new ByteArrayInputStream(bytes)));
      }
    }

    @Test
    @DisplayName("an absolute entry name is taken as relative rather than as the root")
    void treatsAnAbsoluteNameAsRelative() throws Exception {
      final var files =
          skills.unpack(
              new ByteArrayInputStream(
                  zipOf("SKILL.md", "---\nname: x\n---\n", "/etc/passwd", "not really")));

      assertThat(files.keySet()).contains("etc/passwd");
    }

    @Test
    @DisplayName("an archive that unpacks to more than the cap is abandoned, not expanded")
    void refusesAZipBomb() throws Exception {
      // Compresses to a few kilobytes and expands to well over the cap, which is exactly the shape
      // a limit on the uploaded size does not catch.
      final var big = new byte[(int) SkillFiles.MAX_ZIP_BYTES + 1024];
      final var bytes =
          zip(
              new LinkedHashMap<>(
                  Map.of(
                      "SKILL.md",
                      "---\nname: x\n---\n".getBytes(StandardCharsets.UTF_8),
                      "big.bin",
                      big)));
      assertThat(bytes.length).isLessThan((int) SkillFiles.MAX_ZIP_BYTES);

      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> skills.unpack(new ByteArrayInputStream(bytes)));
    }

    @Test
    @DisplayName("an archive with everything in one folder has that folder taken off")
    void stripsASingleTopLevelFolder() throws Exception {
      // What downloading a repository gives you, and unpacked as-is its SKILL.md is a level too
      // deep — which means the folder is not a skill at all.
      final var files =
          skills.unpack(
              new ByteArrayInputStream(
                  zipOf(
                      "my-skill-main/SKILL.md", "---\nname: x\n---\n",
                      "my-skill-main/references/a.md", "kept")));

      assertThat(files.keySet()).containsExactly("SKILL.md", "references/a.md");
    }

    @Test
    @DisplayName("an archive with two top-level folders is unpacked as it is")
    void keepsTwoTopLevelFolders() throws Exception {
      final var files =
          skills.unpack(
              new ByteArrayInputStream(
                  zipOf(
                      "SKILL.md", "---\nname: x\n---\n",
                      "one/a.md", "a",
                      "two/b.md", "b")));

      assertThat(files.keySet()).containsExactly("SKILL.md", "one/a.md", "two/b.md");
    }

    @Test
    @DisplayName("what a Mac puts in an archive is left out of the skill")
    void dropsMacOsClutter() throws Exception {
      final var files =
          skills.unpack(
              new ByteArrayInputStream(
                  zipOf(
                      "SKILL.md", "---\nname: x\n---\n",
                      "__MACOSX/._SKILL.md", "shadow",
                      ".DS_Store", "junk",
                      "references/.DS_Store", "junk")));

      assertThat(files.keySet()).containsExactly("SKILL.md");
    }

    @Test
    @DisplayName("something that is not a zip is refused rather than half read")
    void refusesWhatIsNotAZip() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(
              () ->
                  skills.unpack(
                      new ByteArrayInputStream(
                          "not a zip at all".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("a skill packs to a zip that unpacks back to the same files")
    void roundTrips() throws Exception {
      // The property worth having: what a person downloads is what they can upload again. Both
      // sides speak the skill's own folder, with no wrapper directory around it.
      final var dir = skill("triage", "---\nname: triage\ndescription: d\n---\nSteps.");
      skills.write(home, dir, "references/a.md", "kept");
      skills.write(home, dir, "scripts/run.sh", "#!/bin/sh\necho hi\n");

      final var out = new ByteArrayOutputStream();
      skills.pack(home, dir, out);
      final var back = skills.unpack(new ByteArrayInputStream(out.toByteArray()));

      assertThat(back.keySet())
          .containsExactlyInAnyOrder("SKILL.md", "references/a.md", "scripts/run.sh");
      assertThat(new String(back.get("scripts/run.sh"), StandardCharsets.UTF_8))
          .isEqualTo("#!/bin/sh\necho hi\n");
    }

    @Test
    @DisplayName("packing keeps a file's bytes exactly, whatever they are")
    void packsBytesFaithfully() throws Exception {
      final var dir = skill("triage", "---\nname: triage\n---\n");
      final var bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF, (byte) 0xFE};
      Files.write(dir.resolve("logo.png"), bytes);

      final var out = new ByteArrayOutputStream();
      skills.pack(home, dir, out);

      assertThat(skills.unpack(new ByteArrayInputStream(out.toByteArray())).get("logo.png"))
          .isEqualTo(bytes);
    }

    @Test
    @DisplayName("a symlink planted in a skill is not packed, so a download cannot carry it off")
    void doesNotPackThroughASymlink() throws Exception {
      // Files.walk does not follow links but Files.copy does, so without the guard on the way out
      // the target's contents would be zipped up under an innocent name.
      final var dir = skill("triage", "---\nname: triage\n---\n");
      final var secret = location.resolve("secret.txt");
      Files.writeString(secret, "not yours");
      Files.createSymbolicLink(dir.resolve("out.txt"), secret);

      final var out = new ByteArrayOutputStream();
      assertThatExceptionOfType(SkillAccessDenied.class)
          .isThrownBy(() -> skills.pack(home, dir, out));
      assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).doesNotContain("not yours");
    }

    @Test
    @DisplayName("an unpacked archive is written into the skill, tree and all")
    void writesTheWholeArchive() throws Exception {
      final var dir = skills.target(home, "imported");
      final var files =
          skills.unpack(
              new ByteArrayInputStream(
                  zipOf(
                      "SKILL.md", "---\nname: imported\ndescription: from a zip\n---\nhi",
                      "references/a.md", "kept")));

      assertThat(skills.writeAll(home, dir, files)).isEqualTo(2);
      assertThat(skills.list(home))
          .singleElement()
          .satisfies(
              summary -> {
                assertThat(summary.name()).isEqualTo("imported");
                assertThat(summary.description()).isEqualTo("from a zip");
                assertThat(summary.fileCount()).isEqualTo(2);
              });
    }
  }

  @Test
  @DisplayName("a file is written as UTF-8 whatever the platform's default charset is")
  void writesUtf8() throws Exception {
    final var dir = skill("greeting", "---\nname: greeting\n---\nhi");

    skills.write(home, dir, "notes.md", "你好");

    assertThat(Files.readAllBytes(dir.resolve("notes.md")))
        .isEqualTo("你好".getBytes(StandardCharsets.UTF_8));
  }
}
