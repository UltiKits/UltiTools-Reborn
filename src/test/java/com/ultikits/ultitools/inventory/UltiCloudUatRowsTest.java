package com.ultikits.ultitools.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Checks the gate-3 rows of the UltiCloud protocol v2 client (plan 22-28) in {@code UAT-CHECKLIST.md} and their
 * feature rows in {@code FEATURES.md}, so a row change that breaks the inventory's conventions fails {@code mvn verify}.
 * <p>
 * For every listed checklist ID, not a sample: the checklist row exists once with six cells; its ID, Preconditions,
 * Steps, Expected and Layer cells are non-empty (Covers stays blank, as the file's conventions require when no
 * GUI-excluded class applies); its Layer is a real-server layer of the conventions list; its Preconditions begin with
 * the environment marker {@code Env: `staging`}, {@code Env: `stub`} or {@code Env: `server`}; every checklist ID its
 * Preconditions cite appears earlier in the file (sweep class 8, Phase 10 D-27a). The feature row of its base ID (the
 * ID without a trailing {@code .neg-<slug>} segment) exists once with nine cells, a Kind, Target, Tier and Manual from
 * the conventions' value sets, the Permission the conventions prescribe for its Kind, and a Source whose every
 * {@code ClassName#member} names a class under {@code src/main/java} whose source mentions that member. The retired
 * magic-link rows are absent from both documents, and both documents stay LF.
 * <p>
 * The documents are plain Markdown tables; this is a reader of those tables, nothing more.
 */
class UltiCloudUatRowsTest {

    private static final Path ROOT = Paths.get(System.getProperty("basedir", "."));
    private static final Path FEATURES = ROOT.resolve("FEATURES.md");
    private static final Path CHECKLIST = ROOT.resolve("UAT-CHECKLIST.md");
    private static final Path MAIN_SOURCES = ROOT.resolve("src/main/java");

    /** Every checklist row this plan writes or rewrites, in no particular order. */
    static final List<String> CHECKLIST_IDS = Collections.unmodifiableList(Arrays.asList(
            "ultitools.ulticloud.login",
            "ultitools.ulticloud.login.neg-rcon",
            "ultitools.ulticloud.login.neg-denied",
            "ultitools.ulticloud.login.neg-expired",
            "ultitools.ulticloud.login.neg-already-linked",
            "ultitools.ulticloud.login.neg-rate-limited",
            "ultitools.ulticloud.status",
            "ultitools.ulticloud.status.neg-not-linked",
            "ultitools.ulticloud.logout",
            "ultitools.ulticloud.logout.neg-not-logged-in",
            "ultitools.ulticloud.logout.neg-mid-poll",
            "ultitools.ulticloud.logout.neg-offline",
            "ultitools.ulticloud.credential-revoked",
            "ultitools.ulticloud.upgrade-required",
            "ultitools.ulticloud.reconnect-backoff",
            "ultitools.ulticloud.legacy-token-cleanup",
            "ultitools.ulticloud.plan-limit",
            "ultitools.ulticloud.reconnect",
            "ultitools.ulticloud.reconnect.neg-not-linked",
            "ultitools.ulticloud.v1-silent",
            "ultitools.ulticloud.help",
            "ultitools.ulticloud.help.neg-bare",
            "ultitools.ulticloud.close-superseded",
            "ultitools.ulticloud.close-long-backoff",
            "ultitools.ulticloud.server-id-mismatch",
            "ultitools.cloud.outbox.persist-restart",
            "ultitools.cloud.outbox.overflow",
            "ultitools.cloud.command.ack-result-output",
            "ultitools.cloud.command.dedupe-replay",
            "ultitools.cloud.command.neg-blocked",
            "ultitools.cloud.command.late-output",
            "ultitools.cloud.sensitive.redaction",
            "ultitools.cloud.module.request",
            "ultitools.cloud.module.request.neg-no-responder",
            "ultitools.cloud.credential-rotate",
            "ultitools.cloud.player-link",
            "ultitools.cloud.player-link.redelivery-restart",
            "ultitools.cloud.player-link.neg-ultilogin-2-0-0",
            "ultitools.cloud.power",
            "ultitools.cloud.power.neg-disabled",
            "ultitools.cloud.power.neg-no-restart-script",
            "ultitools.cloud.power.restart-reconnect",
            "ultitools.cloud.player-action",
            "ultitools.cloud.player-action.neg-disabled",
            "ultitools.cloud.player-action.neg-offline-player",
            "ultitools.cloud.snapshot-request",
            "ultitools.cloud.config.edit",
            "ultitools.cloud.config.edit.neg-file-changed",
            "ultitools.cloud.config.edit.neg-invalid-value",
            "ultitools.cloud.files.rw",
            "ultitools.cloud.files.neg-path-not-allowed",
            "ultitools.cloud.files.write.neg-conflict",
            "ultitools.cloud.properties.update",
            "ultitools.cloud.properties.update.neg-refused-key",
            "ultitools.cloud.settings-changed",
            "ultitools.cloud.capability-switch-off",
            "ultitools.upm.install.sha256",
            "ultitools.upm.install.neg-sha256-mismatch",
            "ultitools.upm.install.neg-size-mismatch",
            "ultitools.upm.install.neg-license-required",
            "ultitools.upm.update-check.v2"));

    /** Magic-link rows of 6.3.0 that protocol 2.0.0 retired, in either document. */
    static final List<String> RETIRED_IDS = Collections.unmodifiableList(Arrays.asList(
            "ultitools.ulticloud.request-helper",
            "ultitools.ulticloud.request-helper.neg-not-connected",
            "ultitools.ulticloud.login.neg-already-logged-in",
            "ultitools.ulticloud.status.neg-not-connected"));

    private static final Set<String> LAYERS = new HashSet<>(Arrays.asList(
            "protocol", "java-client", "os-input", "pixel", "server"));
    private static final Set<String> KINDS = new HashSet<>(Arrays.asList(
            "command", "config", "event", "gui", "scheduled", "placeholder", "persistence", "gate"));
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList("player", "console", "both", "n/a"));
    private static final Set<String> TIERS = new HashSet<>(Arrays.asList("player", "admin", "internal"));
    private static final Set<String> MANUALS = new HashSet<>(Arrays.asList("detailed", "brief", "none"));

    private static final Pattern ENV = Pattern.compile("^Env: `(staging|stub|server)`(;|\\.|$)");
    private static final Pattern CITED_ID = Pattern.compile("ultitools(?:\\.[a-z0-9-]+)+");
    private static final Pattern SOURCE_ITEM =
            Pattern.compile("^([A-Z][A-Za-z0-9]*)#([A-Za-z_][A-Za-z0-9_]*)(\\([^)]*\\))?$");
    private static final Pattern CELL_SPLIT = Pattern.compile("(?<!\\\\)\\|");

    /**
     * @param id a listed checklist ID
     * @return whether its row is already checked; every ID is checked once all rows have landed
     */
    static boolean enabled(String id) {
        return "ultitools.ulticloud.login".equals(id);
    }

    @Test
    void everyListedRowHasItsChecklistAndFeatureRow() throws IOException {
        Map<String, List<Row>> checklist = rows(CHECKLIST);
        Map<String, List<Row>> features = rows(FEATURES);
        List<String> problems = new ArrayList<>();
        for (String id : CHECKLIST_IDS) {
            if (enabled(id)) {
                checkChecklistRow(id, checklist.get(id), problems);
                checkFeatureRow(id, base(id), features.get(base(id)), problems);
            }
        }
        assertThat(problems).as("checklist and feature rows of the UltiCloud protocol v2 client").isEmpty();
    }

    @Test
    void preconditionsCiteOnlyEarlierRows() throws IOException {
        Map<String, List<Row>> checklist = rows(CHECKLIST);
        List<String> problems = new ArrayList<>();
        for (String id : CHECKLIST_IDS) {
            List<Row> found = checklist.get(id);
            if (!enabled(id) || found == null || found.size() != 1 || found.get(0).cells.size() < 2) {
                continue;
            }
            Row row = found.get(0);
            Matcher cited = CITED_ID.matcher(row.cells.get(1));
            while (cited.find()) {
                String other = cited.group();
                List<Row> target = checklist.get(other);
                if (other.equals(id) || target == null) {
                    continue;
                }
                if (target.get(0).line >= row.line) {
                    problems.add(id + " (line " + row.line + ") cites " + other + ", which is on line "
                            + target.get(0).line);
                }
            }
        }
        assertThat(problems).as("Preconditions that cite a later row (sweep class 8)").isEmpty();
    }

    @Test
    void bothDocumentsStayLf() throws IOException {
        assertThat(read(FEATURES)).as("FEATURES.md line endings").doesNotContain("\r");
        assertThat(read(CHECKLIST)).as("UAT-CHECKLIST.md line endings").doesNotContain("\r");
    }

    private static void checkChecklistRow(String id, List<Row> found, List<String> problems) {
        if (found == null || found.size() != 1) {
            problems.add(id + ": expected exactly one checklist row, found " + (found == null ? 0 : found.size()));
            return;
        }
        List<String> cells = found.get(0).cells;
        if (cells.size() != 6) {
            problems.add(id + ": checklist row has " + cells.size() + " cells, not 6");
            return;
        }
        String[] names = {"ID", "Preconditions", "Steps", "Expected", "Layer"};
        for (int i = 0; i < names.length; i++) {
            if (cells.get(i).isEmpty()) {
                problems.add(id + ": empty " + names[i] + " cell");
            }
        }
        if (!LAYERS.contains(cells.get(4))) {
            problems.add(id + ": Layer '" + cells.get(4) + "' is not a real-server layer of the conventions");
        }
        if (!ENV.matcher(cells.get(1)).find()) {
            problems.add(id + ": Preconditions do not begin with Env: `staging`, `stub` or `server`");
        }
        if (!cells.get(5).isEmpty()) {
            problems.add(id + ": Covers names '" + cells.get(5) + "', but no GUI-excluded class applies");
        }
    }

    private static void checkFeatureRow(String id, String base, List<Row> found, List<String> problems) {
        if (found == null || found.size() != 1) {
            problems.add(id + ": expected exactly one FEATURES.md row " + base + ", found "
                    + (found == null ? 0 : found.size()));
            return;
        }
        List<String> cells = found.get(0).cells;
        if (cells.size() != 9) {
            problems.add(base + ": feature row has " + cells.size() + " cells, not 9");
            return;
        }
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).isEmpty()) {
                problems.add(base + ": empty feature cell " + (i + 1));
            }
        }
        String kind = cells.get(2);
        String permission = cells.get(4);
        checkValue(base, "Kind", kind, KINDS, problems);
        checkValue(base, "Target", cells.get(5), TARGETS, problems);
        checkValue(base, "Tier", cells.get(6), TIERS, problems);
        checkValue(base, "Manual", cells.get(7), MANUALS, problems);
        if ("command".equals(kind)) {
            if (!"none (requireOp=true)".equals(permission)) {
                problems.add(base + ": a framework cloud command carries 'none (requireOp=true)', not '" + permission
                        + "'");
            }
        } else if (!"n/a".equals(permission) || !"n/a".equals(cells.get(5))) {
            problems.add(base + ": a " + kind + " row carries Permission and Target n/a");
        }
        checkSource(base, cells.get(8), problems);
    }

    private static void checkValue(String base, String column, String value, Set<String> allowed,
                                   List<String> problems) {
        if (!allowed.contains(value)) {
            problems.add(base + ": " + column + " '" + value + "' is not one of " + allowed);
        }
    }

    private static void checkSource(String base, String source, List<String> problems) {
        for (String item : source.split("[,;] ")) {
            Matcher matcher = SOURCE_ITEM.matcher(item.trim());
            if (!matcher.matches()) {
                problems.add(base + ": Source item '" + item + "' is not ClassName#member");
                continue;
            }
            String text = mainSource(matcher.group(1));
            if (text == null) {
                problems.add(base + ": Source class " + matcher.group(1) + " is not under src/main/java");
            } else if (!Pattern.compile("\\b" + Pattern.quote(matcher.group(2)) + "\\b").matcher(text).find()) {
                problems.add(base + ": " + matcher.group(1) + ".java does not mention " + matcher.group(2));
            }
        }
    }

    private static String mainSource(String className) {
        String fileName = className + ".java";
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<Path> matches = files.filter(path -> path.getFileName().toString().equals(fileName))
                    .collect(Collectors.toList());
            return matches.size() == 1 ? read(matches.get(0)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** @return the ID without a trailing {@code .neg-<slug>} segment */
    static String base(String id) {
        int dot = id.lastIndexOf('.');
        return id.substring(dot + 1).startsWith("neg-") ? id.substring(0, dot) : id;
    }

    /** @return every {@code | ultitools...} table row of {@code document}, by its first cell, in file order */
    private static Map<String, List<Row>> rows(Path document) throws IOException {
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        String[] lines = read(document).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("| ultitools.") || !line.trim().endsWith("|")) {
                continue;
            }
            String inner = line.trim();
            inner = inner.substring(1, inner.length() - 1);
            List<String> cells = new ArrayList<>();
            for (String cell : CELL_SPLIT.split(inner, -1)) {
                cells.add(cell.trim());
            }
            rows.computeIfAbsent(cells.get(0), key -> new ArrayList<>()).add(new Row(i + 1, cells));
        }
        return rows;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** One table row: its 1-based line number and its trimmed cells. */
    private static final class Row {
        final int line;
        final List<String> cells;

        Row(int line, List<String> cells) {
            this.line = line;
            this.cells = cells;
        }
    }
}
