package com.ultikits.ultitools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.utils.PluginInstallUtils;

/**
 * Review r4 WR-01 and review r5 WR-01: an update that a crash interrupted must be recovered at the
 * next boot, and a set-aside JAR of an update that FINISHED must never come back.
 * <p>
 * The two cases are told apart by the transaction's journal, {@code <uuid>.txn} in the staging
 * directory: it exists only while a transaction is running. Recovery restores only what a surviving
 * journal records, and a {@code .old} file with no journal is a leftover that is only ever logged.
 * <p>
 * Recovery runs where the module class path is assembled, {@link UltiTools#collectModuleJarUrls},
 * before any module JAR is opened, so its outcome is observed as the class path the server boots
 * with.
 */
@DisplayName("Boot-time recovery of interrupted module updates (reviews r4/r5 WR-01)")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ModuleUpdateRecoveryTest {

    private static final String ID = "fixture-module";
    private static final String UUID_A = "8420a849-1c2d-4e5f-9a0b-1c2d3e4f5a6b";
    private static final String UUID_B = "aaaaaaaa-1c2d-4e5f-9a0b-1c2d3e4f5a6b";
    private static final String OTHER_PROCESS = "4242@another-host";

    @TempDir
    File dataFolder;

    private File pluginsFolder;
    private File stagingFolder;
    private final List<LogRecord> logs = new ArrayList<>();
    private Handler capture;
    private Logger utilsLogger;

    @BeforeEach
    void setUp() {
        pluginsFolder = new File(dataFolder, "plugins");
        stagingFolder = new File(dataFolder, ".upm-staging");
        assertThat(pluginsFolder.mkdirs()).isTrue();
        assertThat(stagingFolder.mkdirs()).isTrue();
        capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        utilsLogger = Logger.getLogger("com.ultikits.ultitools.utils.PluginInstallUtils");
        utilsLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        utilsLogger.removeHandler(capture);
    }

    @Test
    @DisplayName("an interrupted update, its journal still present and its new version absent, restores every jar it set aside")
    void interruptedUpdate_restoresEveryAsideJarOfThatJournal() throws IOException {
        File asideOne = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File asideTwo = setAsideJar(ID + "-1.5.0.jar", UUID_A, "1.5.0");
        byte[] oneBytes = Files.readAllBytes(asideOne.toPath());
        byte[] twoBytes = Files.readAllBytes(asideTwo.toPath());
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", asideOne.getName(), ID + "-1.5.0.jar", asideTwo.getName());

        List<URL> urls = UltiTools.collectModuleJarUrls(pluginsFolder);

        File restoredOne = new File(pluginsFolder, ID + "-1.0.0.jar");
        File restoredTwo = new File(pluginsFolder, ID + "-1.5.0.jar");
        assertThat(restoredOne).hasBinaryContent(oneBytes);
        assertThat(restoredTwo)
                .as("every jar the interrupted transaction set aside is restored, not only the first one listed")
                .hasBinaryContent(twoBytes);
        assertThat(urls).contains(restoredOne.toURI().toURL(), restoredTwo.toURI().toURL());
        assertThat(journal).as("a finished recovery removes the journal").doesNotExist();
        assertThat(stagingFolder.list()).isEmpty();
        assertThat(warnings()).anyMatch(m -> m.contains(restoredOne.getAbsolutePath()));
    }

    @Test
    @DisplayName("review r5 WR-01: a set-aside jar with no journal is never restored, so an uninstalled module stays uninstalled")
    void leftoverWithoutJournal_isNeverRestored() throws IOException {
        File leftover = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("a leftover of a finished update must not resurrect a module the operator uninstalled")
                .doesNotExist();
        assertThat(leftover).exists();
        assertThat(warnings()).anyMatch(m -> m.contains(leftover.getAbsolutePath()) && m.contains("leftover"));
    }

    @Test
    @DisplayName("review r5 WR-01: two leftovers of different transactions restore nothing, so no module is silently downgraded")
    void twoLeftoversWithoutJournal_restoreNothing() throws IOException {
        File older = setAsideJar(ID + "-1.0.0.jar", UUID_B, "1.0.0");
        File newer = setAsideJar(ID + "-1.5.0.jar", UUID_A, "1.5.0");

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(pluginsFolder.list()).isEmpty();
        assertThat(older).exists();
        assertThat(newer).exists();
    }

    @Test
    @DisplayName("a recorded original path taken by another file is skipped, with a warning naming both files")
    void originalPathOccupied_isSkippedAndNamed() throws IOException {
        File occupant = new File(pluginsFolder, ID + "-1.0.0.jar");
        Files.write(occupant.toPath(), "not a module".getBytes(StandardCharsets.UTF_8));
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar", ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(occupant).hasContent("not a module");
        assertThat(aside).exists();
        assertThat(journal)
                .as("codex r8: the set-aside JAR is unresolved, so its record stays for the next start")
                .exists();
        assertThat(warnings()).anyMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains(occupant.getAbsolutePath()));
    }

    @Test
    @DisplayName("a malformed journal is named in a warning and does not stop the next journal's restore")
    void malformedJournal_isIsolated() throws IOException {
        File malformed = new File(stagingFolder, UUID_B + ".txn");
        Files.write(malformed.toPath(), "this is not a journal".getBytes(StandardCharsets.UTF_8));
        File strandedAside = setAsideJar(ID + "-9.0.0.jar", UUID_B, "9.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar", ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(warnings()).anyMatch(m -> m.contains(malformed.getAbsolutePath()));
        assertNotAdvertisedAsDeletable(strandedAside);
    }

    @Test
    @DisplayName("a journal that cannot be read at all is named in a warning and does not stop the next journal's restore")
    void unreadableJournal_isIsolated() throws IOException {
        File unreadable = new File(stagingFolder, UUID_B + ".txn");
        assertThat(unreadable.mkdir()).as("a directory in the journal's place cannot be read as one").isTrue();
        File strandedAside = setAsideJar(ID + "-9.0.0.jar", UUID_B, "9.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar", ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(warnings()).anyMatch(m -> m.contains(unreadable.getAbsolutePath()));
        assertNotAdvertisedAsDeletable(strandedAside);
    }

    @Test
    @DisplayName("review r5 IN-04: a journal written by this JVM belongs to a running update and is left alone")
    void journalOfTheCurrentProcess_isSkipped() throws Exception {
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeJournal(UUID_A, currentProcessIdentity(), ID,
                ID + "-2.0.0.jar", ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("restoring beside a running update of the same module would install two versions")
                .doesNotExist();
        assertThat(aside).exists();
        assertThat(journal).exists();
        assertNotAdvertisedAsDeletable(aside);
    }

    @Test
    @DisplayName("codex r8 P2: an occupied original path keeps the journal, so its JAR is never called disposable")
    void originalPathOccupied_keepsTheJournal() throws IOException {
        File occupant = new File(pluginsFolder, ID + "-1.0.0.jar");
        Files.write(occupant.toPath(), "not the module".getBytes(StandardCharsets.UTF_8));
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(occupant).hasContent("not the module");
        assertThat(aside).exists();
        assertThat(journal)
                .as("the set-aside JAR is still unresolved, so the record of it must stay")
                .exists();
        assertNotAdvertisedAsDeletable(aside);
    }

    @Test
    @DisplayName("codex r18 P2: a gap in a journal's pair indices keeps the journal, and its JARs protected")
    void journalWithAGapInItsPairIndices_isKept() throws IOException {
        File first = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File afterTheGap = setAsideJar(ID + "-9.0.0.jar", UUID_A, "9.0.0");
        File journal = new File(stagingFolder, UUID_A + ".txn");
        String text = "format=1\nprocess=" + OTHER_PROCESS + "\nmodule=" + ID + "\ntarget=" + ID + "-2.0.0.jar\n"
                + "aside.0.original=" + ID + "-1.0.0.jar\naside.0.aside=" + first.getName() + "\n"
                + "aside.2.original=" + ID + "-9.0.0.jar\naside.2.aside=" + afterTheGap.getName() + "\n";
        Files.write(journal.toPath(), text.getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(journal)
                .as("a pair this recovery never read is a reason to keep the record of it")
                .exists();
        assertThat(afterTheGap).exists();
        assertNotAdvertisedAsDeletable(afterTheGap);
    }

    @Test
    @DisplayName("review r6 IN-02: one unusable name in a journal does not abandon that journal's other JARs")
    void oneUnusableNameInAJournal_skipsOnlyThatPair() throws IOException {
        File good = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File skipped = setAsideJar(ID + "-9.0.0.jar", UUID_A, "9.0.0");
        File journal = new File(stagingFolder, UUID_A + ".txn");
        String text = "format=1\nprocess=" + OTHER_PROCESS + "\nmodule=" + ID + "\ntarget=" + ID + "-2.0.0.jar\n"
                + "aside.0.original=../escape.jar\naside.0.aside=" + skipped.getName() + "\n"
                + "aside.1.original=" + ID + "-1.0.0.jar\naside.1.aside=" + good.getName() + "\n";
        Files.write(journal.toPath(), text.getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("the pair that can be used is restored; only the unusable one is skipped")
                .exists();
        assertThat(new File(dataFolder, "escape.jar")).doesNotExist();
        assertThat(warnings()).anyMatch(m -> m.contains(journal.getAbsolutePath()));
        assertThat(journal)
                .as("codex r6: a journal with an unresolved pair is kept, so its JAR stays protected")
                .exists();
        assertThat(skipped).exists();
        assertNotAdvertisedAsDeletable(skipped);
    }

    @Test
    @DisplayName("a stale partial download is deleted")
    void stalePartialDownload_isDeleted() throws IOException {
        File part = new File(stagingFolder, ID + "-2.0.0-" + UUID_A + ".part");
        Files.write(part.toPath(), "partial".getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(part).doesNotExist();
        assertThat(logs).anyMatch(r -> r.getLevel() == Level.INFO && r.getMessage().contains(part.getAbsolutePath()));
    }

    @Test
    @DisplayName("files that are neither a journal, a set-aside jar nor a partial download are left untouched")
    void unrelatedStagingFiles_areLeftUntouched() throws IOException {
        File noUuid = writeJar(new File(stagingFolder, ID + "-1.0.0.jar.old"), ID, "1.0.0");
        File badUuid = writeJar(new File(stagingFolder, ID + "-1.0.0.jar.not-a-uuid.old"), ID, "1.0.0");
        File notes = new File(stagingFolder, "notes.txt");
        Files.write(notes.toPath(), "keep me".getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(noUuid).exists();
        assertThat(badUuid).exists();
        assertThat(notes).exists();
        assertThat(pluginsFolder.list()).isEmpty();
    }

    @Test
    @DisplayName("a staging path that is not a directory never breaks building the class path")
    void stagingPathIsAFile_neverBreaksBoot() throws IOException {
        assertThat(stagingFolder.delete()).isTrue();
        assertThat(stagingFolder.createNewFile()).isTrue();
        File module = writeJar(new File(pluginsFolder, ID + "-1.0.0.jar"), ID, "1.0.0");

        assertThatCode(() -> assertThat(UltiTools.collectModuleJarUrls(pluginsFolder))
                .contains(module.toURI().toURL())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("review r6 BL-01: of two journals differing only in start time, this run's is skipped and the earlier run's is recovered")
    void journalOfAnEarlierStartOfTheSameJvmName_isRecovered() throws Exception {
        String mine = currentProcessIdentity();
        String earlier = earlierStartOf(mine);
        assertThat(earlier).as("the two identities must differ only in the start time").isNotEqualTo(mine);
        File runningAside = setAsideJar(ID + "-3.0.0.jar", UUID_B, "3.0.0");
        File runningJournal = writeJournal(UUID_B, mine, ID, ID + "-4.0.0.jar",
                ID + "-3.0.0.jar", runningAside.getName());
        File crashedAside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File crashedJournal = writeJournal(UUID_A, earlier, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", crashedAside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("a container restart repeats the JVM name, so only the start time tells the runs apart")
                .exists();
        assertThat(crashedAside).doesNotExist();
        assertThat(crashedJournal).doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-3.0.0.jar"))
                .as("this run's own transaction must not be undone beneath it")
                .doesNotExist();
        assertThat(runningAside).exists();
        assertThat(runningJournal).exists();
    }

    @Test
    @DisplayName("codex r6 P1: a journal of an earlier run that reused this PID and host is recovered, not skipped")
    void journalOfAnEarlierRunOnTheSamePidAndHost_isRecovered() throws IOException {
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        // A container that runs Java as PID 1 under a fixed hostname produces the same
        // RuntimeMXBean name on every start, so that name alone cannot say "this run".
        File journal = writeJournal(UUID_A, ManagementFactory.getRuntimeMXBean().getName(), ID,
                ID + "-2.0.0.jar", ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("the crashed run's journal must not be mistaken for this run's")
                .exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("codex r6 P2: a restore that fails keeps the journal, and the next boot restores the JAR")
    void aFailedRestoreKeepsTheJournalForTheNextBoot() throws IOException {
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());
        // A modules folder that cannot be written to: the restore fails for a reason that can pass.
        assertThat(pluginsFolder.delete()).isTrue();
        Files.write(pluginsFolder.toPath(), "not a folder".getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(aside).exists();
        assertThat(journal)
                .as("without the journal the set-aside JAR becomes a leftover nothing ever restores")
                .exists();
        assertThat(warnings()).anyMatch(m -> m.contains(aside.getAbsolutePath()));

        assertThat(pluginsFolder.delete()).isTrue();
        assertThat(pluginsFolder.mkdirs()).isTrue();
        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("codex r6 P2: a target path held by an unrelated file does not count as an installed update")
    void journalWhoseTargetIsAnUnrelatedFile_restoresTheOldJars() throws IOException {
        File occupant = new File(pluginsFolder, ID + "-2.0.0.jar");
        Files.write(occupant.toPath(), "not a module JAR".getBytes(StandardCharsets.UTF_8));
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("the update never installed its new version, so the module's own JAR must come back")
                .exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
        assertThat(warnings()).noneMatch(m -> m.contains("leftover") && m.contains(aside.getAbsolutePath()));
    }

    @Test
    @DisplayName("an aside a journal awaiting confirmation still refers to is not advertised as deletable")
    void asideOfAnAwaitingJournal_isNotAdvertisedAsDeletable() throws IOException {
        writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(warnings())
                .as("the confirmation hook still needs this JAR, so nothing may invite the operator to delete it")
                .noneMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains("no interrupted update refers to it"));
    }

    @Test
    @DisplayName("codex r22 P1: the pre-load recovery hook leaves an awaiting journal for the confirmation hook")
    void awaitingJournal_survivesThePreLoadRecoveryHook() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        // The real boot order: the pre-load hook first, then the modules load, then confirmation.
        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(journal)
                .as("deleting it here leaves nothing for the confirmation hook, and the rollback never happens")
                .exists();
        assertThat(aside).exists();
        assertThat(installed).exists();

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("SomethingElse"));

        assertThat(installed).as("the module did not load, so the update is rolled back").doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("redesign: a module that loaded after its update confirms the update, and the old JAR goes")
    void updateConfirmedByTheNextBoot_deletesTheOldJarAndTheJournal() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList(ID));

        assertThat(installed).as("the module loaded, so the new version stays").exists();
        assertThat(aside).as("nothing needs the old version once the new one has loaded").doesNotExist();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("redesign: a module that did not load after its update is rolled back to the version that did")
    void updateNotConfirmedByTheNextBoot_isRolledBack() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("SomethingElse"));

        assertThat(installed).as("the version that did not load must not be there at the next start").doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("the version that did load is put back where it was")
                .exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
        assertThat(messagesAtLeastWarning())
                .as("the operator must be told the module is absent now and back at the next restart")
                .anyMatch(m -> m.contains("Fixture") && m.contains("1.0.0") && m.contains("restart"));
    }

    @Test
    @DisplayName("redesign: a failure while confirming an update never breaks boot")
    void confirmationFailure_neverBreaksBoot() throws IOException {
        File staging = new File(dataFolder, ".upm-staging");
        File unreadable = new File(staging, UUID_A + ".txn");
        assertThat(unreadable.mkdir()).as("a directory in a journal's place cannot be read as one").isTrue();

        assertThatCode(() -> PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("Fixture")))
                .as("boot must continue whatever this hook meets")
                .doesNotThrowAnyException();
        assertThatCode(() -> PluginInstallUtils.confirmUpdatesAfterBoot(null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("redesign: a journal still moving JARs is not a confirmation candidate")
    void journalStillMovingJars_isNotConfirmedOrRolledBack() throws Exception {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        // A transaction of the process running now: it is still moving JARs, not a crashed one.
        File journal = writeJournal(UUID_A, currentProcessIdentity(), ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("SomethingElse"));

        assertThat(installed).as("a transaction of this process is not this hook's business").exists();
        assertThat(aside).exists();
        assertThat(journal).exists();
    }

    @Test
    @DisplayName("codex r23 P1: another module answering to the same name does not confirm this update")
    void updateOfAModuleThatDidNotLoad_isNotConfirmedByASharedName() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        // What loaded is a different module whose plugin.yml happens to carry the same name.
        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Arrays.asList("Fixture", "other-module"));

        assertThat(installed)
                .as("confirming on the name alone deletes the only copy that is known to load")
                .doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("codex r23 P2: a rollback that could not restore every old JAR keeps its journal")
    void rollbackThatCouldNotRestoreEveryJar_keepsTheJournal() throws IOException {
        writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File first = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File second = setAsideJar(ID + "-0.9.0.jar", UUID_A, "0.9.0");
        // One of the two paths the rollback has to write to is taken by something else.
        File occupant = new File(pluginsFolder, ID + "-0.9.0.jar");
        Files.write(occupant.toPath(), "in the way".getBytes(StandardCharsets.UTF_8));
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", first.getName(), ID + "-0.9.0.jar", second.getName());

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("other-module"));

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).as("what could be restored is restored").exists();
        assertThat(second).as("nothing may drop a JAR the rollback has not placed yet").exists();
        assertThat(journal)
                .as("deleting it strands that JAR in staging with no record to retry it from")
                .exists();
        assertThat(messagesAtLeastWarning())
                .anyMatch(m -> m.contains(journal.getAbsolutePath()));
    }

    @Test
    @DisplayName("sweep A8: a crash between installing the new version and marking the journal leaves it for confirmation")
    void installedButUnmarkedJournal_isLeftForBootConfirmation() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        // The journal an update writes before it moves anything: no phase recorded yet.
        File journal = writeJournal(UUID_A, OTHER_PROCESS, ID, ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(journal)
                .as("the new version is installed, so only the next boot can say whether it loads")
                .exists();
        assertThat(aside).as("the version that did load is the rollback's only material").exists();

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("other-module"));

        assertThat(installed).as("it did not load, so it is rolled back").doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("sweep A13: a rollback that was interrupted is finished at the next boot, not confirmed")
    void interruptedRollback_isFinishedAtTheNextBoot() throws IOException {
        writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File first = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File second = setAsideJar(ID + "-0.9.0.jar", UUID_A, "0.9.0");
        File occupant = new File(pluginsFolder, ID + "-0.9.0.jar");
        Files.write(occupant.toPath(), "in the way".getBytes(StandardCharsets.UTF_8));
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", first.getName(), ID + "-0.9.0.jar", second.getName());

        // First boot: the module did not load, one JAR goes back, the other cannot, journal kept.
        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("other-module"));
        assertThat(journal).exists();
        assertThat(second).exists();

        // Second boot, in the real order: the pre-load hook runs before the modules load. A journal
        // recording rollback work needs nothing from the load phase, so it is finished here - and
        // the JAR it puts back is on the class path in time to load this session.
        assertThat(occupant.delete()).isTrue();
        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-0.9.0.jar"))
                .as("deferring this to the confirmation hook costs the module another restart")
                .exists();
        assertThat(second).doesNotExist();
        assertThat(journal).doesNotExist();
        assertNotAdvertisedAsDeletable2(first);
    }

    @Test
    @DisplayName("sweep A-rollback: an update with no previous version to restore does not repeat every boot")
    void rollbackWithNoPreviousVersion_deletesItsJournal() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        // A first-time install or a module whose own JAR was removed: the update set nothing aside.
        File journal = writeAwaitingConfirmation(UUID_A, ID, "Fixture", ID + "-2.0.0.jar");

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList("other-module"));

        assertThat(installed).doesNotExist();
        assertThat(journal)
                .as("keeping it repeats the same failure at every start with nothing left to do")
                .doesNotExist();
        assertThat(messagesAtLeastWarning())
                .anyMatch(m -> m.contains("Fixture") && m.contains("not installed"));
    }

    @Test
    @DisplayName("sweep C1: confirmation keeps a journal whose pair list could not be read in full")
    void confirmationOfAJournalWithAGap_keepsIt() throws IOException {
        File installed = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File first = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File afterTheGap = setAsideJar(ID + "-0.9.0.jar", UUID_A, "0.9.0");
        File journal = new File(new File(dataFolder, ".upm-staging"), UUID_A + ".txn");
        Files.write(journal.toPath(), ("format=1\nprocess=" + OTHER_PROCESS + "\nmodule=" + ID + "\n"
                + "name=Fixture\ntarget=" + ID + "-2.0.0.jar\nphase=awaiting-boot-confirmation\n"
                + "aside.0.original=" + ID + "-1.0.0.jar\naside.0.aside=" + first.getName() + "\n"
                + "aside.2.original=" + ID + "-0.9.0.jar\naside.2.aside=" + afterTheGap.getName() + "\n")
                .getBytes(StandardCharsets.UTF_8));

        PluginInstallUtils.confirmUpdatesAfterBoot(dataFolder, Collections.singletonList(ID));

        assertThat(installed).as("the module loaded, so its new version stays").exists();
        assertThat(afterTheGap)
                .as("a JAR past the gap was never read, and deleting its journal strands it")
                .exists();
        assertThat(journal).exists();
    }

    @Test
    @DisplayName("sweep A12: a rejected candidate is not left on the class path for the load phase")
    void rollingBackJournal_isFinishedBeforeTheModulesLoad() throws IOException {
        File rejected = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File journal = writeRollingBack(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-1.0.0.jar", aside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(rejected)
                .as("the verdict was already made, so nothing has to wait for the modules to load")
                .doesNotExist();
        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar")).exists();
        assertThat(aside).doesNotExist();
        assertThat(journal).doesNotExist();
    }

    @Test
    @DisplayName("sweep A21: a rollback retried does not delete the JAR the first attempt put back")
    void rollbackRetried_keepsWhatTheFirstAttemptRestored() throws IOException {
        // A same-version retry: the JAR set aside came from the path the candidate was installed to.
        File restored = writeJar(new File(pluginsFolder, ID + "-2.0.0.jar"), ID, "2.0.0");
        byte[] restoredBytes = Files.readAllBytes(restored.toPath());
        File secondAside = setAsideJar(ID + "-0.9.0.jar", UUID_A, "0.9.0");
        File occupant = new File(pluginsFolder, ID + "-0.9.0.jar");
        Files.write(occupant.toPath(), "in the way".getBytes(StandardCharsets.UTF_8));
        // The first attempt restored the first pair, and its set-aside file is gone; the second
        // could not be placed, so the journal is still here.
        File journal = writeRollingBack(UUID_A, ID, "Fixture", ID + "-2.0.0.jar",
                ID + "-2.0.0.jar", ID + "-2.0.0.jar." + UUID_A + ".old",
                ID + "-0.9.0.jar", secondAside.getName());

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(restored)
                .as("deleting this deletes the only copy the rollback had, and its set-aside file is gone")
                .exists();
        assertThat(restored).hasBinaryContent(restoredBytes);
        assertThat(journal).as("the second pair still has nowhere to go").exists();
    }

    @Test
    @DisplayName("sweep A18: a journal whose replacement was interrupted mid-publish is adopted, not deleted")
    void completeTemporaryJournal_isAdoptedWhenTheJournalIsGone() throws IOException {
        File aside = setAsideJar(ID + "-1.0.0.jar", UUID_A, "1.0.0");
        File staging = new File(dataFolder, ".upm-staging");
        // What a file store that refuses an atomic replace leaves if the process dies between
        // removing the old journal and renaming its replacement into place.
        File temporary = new File(staging, UUID_A + ".txn.tmp");
        Files.write(temporary.toPath(), ("format=1\nprocess=" + OTHER_PROCESS + "\nmodule=" + ID + "\n"
                + "name=Fixture\ntarget=" + ID + "-2.0.0.jar\n"
                + "aside.0.original=" + ID + "-1.0.0.jar\naside.0.aside=" + aside.getName() + "\n")
                .getBytes(StandardCharsets.UTF_8));

        UltiTools.collectModuleJarUrls(pluginsFolder);

        assertThat(new File(pluginsFolder, ID + "-1.0.0.jar"))
                .as("deleting it as a stale temporary file leaves the module with no JAR at all")
                .exists();
        assertThat(aside).doesNotExist();
        assertThat(temporary).doesNotExist();
    }

    /** A journal whose update was rejected and whose rollback has not finished. */
    private File writeRollingBack(String transaction, String module, String name, String target,
                                  String... originalAndAsideNames) throws IOException {
        File journal = writeJournal(transaction, OTHER_PROCESS, module, target, originalAndAsideNames);
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8)
                + "name=" + name + "\nphase=rolling-back\n";
        Files.write(journal.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return journal;
    }

    /** A JAR the rollback put back is accounted for, not advertised as a deletable leftover. */
    private void assertNotAdvertisedAsDeletable2(File aside) {
        assertThat(warnings())
                .noneMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains("no interrupted update refers to it"));
    }

    /** A journal of an update that installed its new version and is waiting for the next boot. */
    private File writeAwaitingConfirmation(String transaction, String module, String name, String target,
                                           String... originalAndAsideNames) throws IOException {
        File journal = writeJournal(transaction, OTHER_PROCESS, module, target, originalAndAsideNames);
        String text = new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8)
                + "name=" + name + "\nphase=awaiting-boot-confirmation\n";
        Files.write(journal.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return journal;
    }

    /**
     * Review r6 WR-03: a set-aside JAR that a surviving journal still refers to must never be called
     * deletable, and must be named as belonging to a journal that was kept.
     */
    private void assertNotAdvertisedAsDeletable(File aside) {
        assertThat(warnings())
                .as("a JAR a retained journal refers to must not be advertised as deletable")
                .noneMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains("no interrupted update refers to it"));
        assertThat(warnings())
                .as("the operator must be told that this JAR belongs to a journal that was kept")
                .anyMatch(m -> m.contains(aside.getAbsolutePath()) && m.contains("do not delete"));
    }

    /** The same identity with an earlier start time: what the previous run of this JVM name wrote. */
    private static String earlierStartOf(String identity) {
        int marker = identity.lastIndexOf('#');
        assertThat(marker).as("the identity must carry a start-time component: %s", identity).isPositive();
        return identity.substring(0, marker + 1) + (Long.parseLong(identity.substring(marker + 1)) - 1L);
    }

    /** The identity the framework writes into a journal, read from the class that writes it. */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
    private static String currentProcessIdentity() throws Exception {
        java.lang.reflect.Method method = Class.forName("com.ultikits.ultitools.utils.PluginInstallUtils")
                .getDeclaredMethod("currentProcessIdentity");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    /** Every message at WARNING or above: a rollback is reported at SEVERE. */
    private List<String> messagesAtLeastWarning() {
        return logs.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage).collect(Collectors.toList());
    }

    private List<String> warnings() {
        return logs.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .collect(Collectors.toList());
    }

    /** Writes a set-aside JAR named as an update's move-aside step names it. */
    private File setAsideJar(String originalName, String transaction, String version) throws IOException {
        return writeJar(new File(stagingFolder, originalName + "." + transaction + ".old"), ID, version);
    }

    /**
     * Writes a transaction journal in the format the update writes: a properties file naming the
     * module, the new version's file name, the writing JVM and each original/set-aside pair.
     */
    private File writeJournal(String transaction, String process, String module, String target,
                              String... originalAndAsideNames) throws IOException {
        StringBuilder text = new StringBuilder(256);
        text.append("format=1\n").append("process=").append(process).append('\n')
                .append("module=").append(module).append('\n')
                .append("target=").append(target).append('\n');
        for (int i = 0; i < originalAndAsideNames.length; i += 2) {
            text.append("aside.").append(i / 2).append(".original=").append(originalAndAsideNames[i]).append('\n');
            text.append("aside.").append(i / 2).append(".aside=").append(originalAndAsideNames[i + 1]).append('\n');
        }
        File journal = new File(stagingFolder, transaction + ".txn");
        Files.write(journal.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return journal;
    }

    private static File writeJar(File file, String identifyString, String version) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: Fixture\nversion: " + version + "\nidentify-string: " + identifyString + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }
}
