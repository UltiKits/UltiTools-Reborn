package com.ultikits.ultitools.utils;

import static com.ultikits.ultitools.text.Arg.number;
import static com.ultikits.ultitools.text.Arg.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.FixedLocaleResolver;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.text.internal.CatalogueLoader;
import com.ultikits.ultitools.text.internal.MessageRenderer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class UpmLegacyTextParityTest {
    private static final Pattern HEX = Pattern.compile("<#([0-9a-f]{6})>");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final CatalogueLoader.Loaded SHIPPED = new CatalogueLoader().load(MessageKey.FRAMEWORK_NAMESPACE,
            Locale.ENGLISH, UpmKeys.class.getProtectionDomain().getCodeSource().getLocation());
    private static final List<Row> ROWS = List.of(
            new Row("upm.list.header", "<#00ffff>{0}",
                    List.of("========|可用插件列表|========\n"),
                    List.of()),
            new Row("upm.list.entry.name", "<#00ffff>%s{0}%s",
                    List.of(".  名字："),
                    List.of(number("index", 42), string("name", "name-sample"))),
            new Row("upm.list.entry.status.installed", "<#7f7f7f>{0}<#00ff00>{1}",
                    List.of("    安装状态：", " 已安装"),
                    List.of()),
            new Row("upm.list.entry.status.not_installed", "<#7f7f7f>{0}<#ff0000>{1}",
                    List.of("    安装状态：", " 未安装"),
                    List.of()),
            new Row("upm.list.entry.uninstall_button", "<#ff0000>{0}",
                    List.of("     | 卸载 |     "),
                    List.of()),
            new Row("upm.list.entry.uninstall_hover", "{0}",
                    List.of("点击卸载模块"),
                    List.of()),
            new Row("upm.list.entry.install_button", "<#00ff00>{0}",
                    List.of("     | 安装 |     "),
                    List.of()),
            new Row("upm.list.entry.install_hover", "{0}",
                    List.of("点击安装模块"),
                    List.of()),
            new Row("upm.list.entry.intro", "<#7f7f7f>{0}%s",
                    List.of("   简介："),
                    List.of(string("description", "description-sample"))),
            new Row("upm.list.entry.install_command", "{0}%s",
                    List.of("   安装命令：/upm install "),
                    List.of(string("module", "module-sample"))),
            new Row("upm.list.page", "<#00ffff>{0}",
                    List.of("======== 第%d页 ========"),
                    List.of(number("page", 42))),
            new Row("upm.versions.not_found", "§c{0}",
                    List.of("UltiCloud 上没有找到模块 %s。"),
                    List.of(string("module", "module-sample"))),
            new Row("upm.versions.failed", "§c{0}",
                    List.of("获取版本列表失败！"),
                    List.of()),
            new Row("upm.versions.none_installable", "§c{0}",
                    List.of("%s 没有可以安装在本服务器上的版本。本 UltiTools-API 的 API 级别是 %d，只安装声明 api-version %d 到 %d 的模块。"),
                    List.of(string("module", "module-sample"), number("level", 42), number("floor", 42), number("max", 42))),
            new Row("upm.versions.header", "{0}",
                    List.of("========|可用版本列表|========\n"),
                    List.of()),
            new Row("upm.versions.entry", "%s{0}%s",
                    List.of(". 版本："),
                    List.of(number("index", 42), string("version", "version-sample"))),
            new Row("upm.versions.install_command", "{0}%s [version]",
                    List.of("   安装命令：/upm install "),
                    List.of(string("module", "module-sample"))),
            new Row("upm.check.none", "§a{0}",
                    List.of("没有可用的更新。"),
                    List.of()),
            new Row("upm.check.header", "§a{0}",
                    List.of("可用更新列表："),
                    List.of()),
            new Row("upm.check.framework", "§e  UltiTools-API %s → %s",
                    List.of(),
                    List.of(string("current", "current-sample"), string("latest", "latest-sample"))),
            new Row("upm.check.download", "§7  {0}",
                    List.of("下载地址：%s"),
                    List.of(string("url", "url-sample"))),
            new Row("upm.check.module", "§e  %s %s → %s",
                    List.of(),
                    List.of(string("module", "module-sample"), string("current", "current-sample"), string("latest", "latest-sample"))),
            new Row("upm.check.failed", "§e{0}",
                    List.of("更新检查失败，详情见控制台；重启服务器后会再次检查。"),
                    List.of()),
            new Row("upm.check.running", "§e{0}",
                    List.of("更新检查仍在进行，请稍后重试。"),
                    List.of()),
            new Row("upm.check.incomplete", "§e{0}",
                    List.of("更新检查未完成：无法确认 %s 的最新版本。"),
                    List.of(string("modules", "modules-sample"))),
            new Row("upm.update.none", "§c{0}",
                    List.of("没有可用的更新。"),
                    List.of()),
            new Row("upm.update.updating", "§e{0}",
                    List.of("正在更新 %s..."),
                    List.of(string("module", "module-sample"))),
            new Row("upm.help.title", "§a{0}",
                    List.of("========|插件安装帮助|========"),
                    List.of()),
            new Row("upm.help.list", "§a{0}",
                    List.of("/upm list [页数] - 查看可用插件列表"),
                    List.of()),
            new Row("upm.help.install", "§a{0}",
                    List.of("/upm install [插件] - 安装最新插件"),
                    List.of()),
            new Row("upm.help.install_version", "§a{0}",
                    List.of("/upm install [插件] [版本] - 安装某版本插件"),
                    List.of()),
            new Row("upm.help.versions", "§a{0}",
                    List.of("/upm versions [插件] - 查看插件版本列表"),
                    List.of()),
            new Row("upm.help.uninstall", "§a{0}",
                    List.of("/upm uninstall [插件] - 删除插件"),
                    List.of()),
            new Row("upm.help.check", "§a{0}",
                    List.of("/upm check - 查看可用更新"),
                    List.of()),
            new Row("upm.help.update", "§a{0}",
                    List.of("/upm update [插件] - 更新插件"),
                    List.of()),
            new Row("upm.help.update_all", "§a{0}",
                    List.of("/upm update all - 更新所有插件"),
                    List.of()),
            new Row("upm.start.update.committed", "{0}",
                    List.of("模块 %s 已从 %s 更新到 %s：本次启动确认新版本已加载，旧版本 JAR 已删除。"),
                    List.of(string("module", "module-sample"), string("old_version", "old_version-sample"), string("new_version", "new_version-sample"))),
            new Row("upm.start.update.rolled_back", "{0}",
                    List.of("模块 %s 更新到 %s 后没有加载，已恢复为 %s；%s 将在下次启动时加载。"),
                    List.of(string("module", "module-sample"), string("new_version", "new_version-sample"), string("old_version", "old_version-sample"), string("loaded", "loaded-sample"))),
            new Row("upm.start.update.rollback_no_backup", "{0}",
                    List.of("模块 %s 保留的旧版本 JAR（%s）不见了，无法回滚；%s 保持不动。"),
                    List.of(string("module", "module-sample"), string("backup", "backup-sample"), string("target", "target-sample"))),
            new Row("upm.start.update.unconfirmed_rolled_back", "{0}",
                    List.of("上次启动在确认模块 %s 的 %s 版本是否加载之前就结束了；已恢复为 %s，本次启动加载 %s。"),
                    List.of(string("module", "module-sample"), string("new_version", "new_version-sample"), string("old_version", "old_version-sample"), string("loaded", "loaded-sample"))),
            new Row("upm.start.update.rollback_deferred", "{0}",
                    List.of("模块 %s 更新到 %s 后没有加载；恢复 %s 未能在本次完成（%s），将在下次启动、加载模块之前完成。"),
                    List.of(string("module", "module-sample"), string("new_version", "new_version-sample"), string("old_version", "old_version-sample"), string("error", "error-sample"))),
            new Row("upm.start.update.rollback_failed_before_load", "{0}",
                    List.of("模块 %s 从 %s 恢复为 %s 未能完成（%s）；下次启动会再试。"),
                    List.of(string("module", "module-sample"), string("new_version", "new_version-sample"), string("old_version", "old_version-sample"), string("error", "error-sample"))),
            new Row("upm.start.update.rollback_finished", "{0}",
                    List.of("模块 %s 的回滚已完成：已恢复为 %s，本次启动加载 %s。"),
                    List.of(string("module", "module-sample"), string("old_version", "old_version-sample"), string("loaded", "loaded-sample"))),
            new Row("upm.start.update.apply_failed", "{0}",
                    List.of("模块 %s 的更新（%s → %s）未能应用：%s：%s。模块目录未改变，本次启动加载 %s；下次执行 /upm update 时会再次报告。"),
                    List.of(string("module", "module-sample"), string("old_version", "old_version-sample"), string("new_version", "new_version-sample"), string("file", "file-sample"), string("error", "error-sample"), string("loaded", "loaded-sample"))),
            new Row("upm.start.update.apply_cross_file_system", "{0}",
                    List.of("模块 %s 的更新（%s → %s）未能应用：模块目录 %s 与更新目录 %s 不在同一个文件系统上，无法原子移动。模块目录未改变，本次启动加载 %s；请把两个目录放到同一个文件系统上，再执行 /upm update。"),
                    List.of(string("module", "module-sample"), string("old_version", "old_version-sample"), string("new_version", "new_version-sample"), string("modules_folder", "modules_folder-sample"), string("update_folder", "update_folder-sample"), string("loaded", "loaded-sample"))),
            new Row("upm.start.update.staged_jar_mismatch", "{0}",
                    List.of("模块 %s 暂存的 JAR %s 已不是暂存时下载的文件（预期 SHA-256 %s，实际 %s），更新未应用；模块目录未改变，下次执行 /upm update 时会再次报告。"),
                    List.of(string("module", "module-sample"), string("file", "file-sample"), string("expected", "expected-sample"), string("actual", "actual-sample"))),
            new Row("upm.start.update.apply_failed_unrestored", "{0}",
                    List.of("模块 %s 的更新（%s → %s）未能应用：%s：%s；把旧版本移回也失败了（%s）。旧版本 JAR 在 %s，请停止服务器后手动移回 %s。"),
                    List.of(string("module", "module-sample"), string("old_version", "old_version-sample"), string("new_version", "new_version-sample"), string("file", "file-sample"), string("error", "error-sample"), string("restore_error", "restore_error-sample"), string("backup", "backup-sample"), string("target", "target-sample"))),
            new Row("upm.start.update.restore_failed", "{0}",
                    List.of("模块 %s 的旧版本 JAR 仍在 %s，未能移回 %s（%s）；请停止服务器后手动移回。"),
                    List.of(string("module", "module-sample"), string("backup", "backup-sample"), string("target", "target-sample"), string("error", "error-sample"))),
            new Row("upm.start.update.cleanup_deferred", "{0}",
                    List.of("模块 %s 的更新已确认，但更新目录 %s 未能清理，下次启动会再清理。"),
                    List.of(string("module", "module-sample"), string("folder", "folder-sample"))),
            new Row("upm.start.update.abandoned", "{0}",
                    List.of("模块 %s 暂存的 %s 版本更新已放弃：它的 JAR %s 在暂存之后已不在模块目录中（例如已卸载）。"),
                    List.of(string("module", "module-sample"), string("version", "version-sample"), string("file", "file-sample"))),
            new Row("upm.start.update.needs_operator", "{0}",
                    List.of("模块 %s 的更新已暂停，等待处理：%s。在这些文件与更新记录一致之前，更新不会移动或删除任何文件，/upm update 和 /upm uninstall 也会拒绝该模块。请检查这些文件（需要的 JAR 可从更新目录中取回），然后删除更新记录 %s 及其同名文件夹，以放弃这次更新。"),
                    List.of(string("module", "module-sample"), string("failure", "failure-sample"), string("file", "file-sample"))),
            new Row("upm.start.update.orphan_backup", "{0}",
                    List.of("更新目录 %s 中有不属于任何更新记录的旧版本 JAR，已保留：%s"),
                    List.of(string("folder", "folder-sample"), string("files", "files-sample"))),
            new Row("upm.start.records.unlistable", "{0}",
                    List.of("更新目录 %s 无法列出（%s）；本次启动没有执行任何暂存的更新或卸载时记录的删除，它们保持原样，下次启动会再试。"),
                    List.of(string("folder", "folder-sample"), string("error", "error-sample"))),
            new Row("upm.start.record.transaction_error", "{0}",
                    List.of("更新记录 %s 处理时出错（%s）；记录保持原样，下次启动会从它记录的步骤继续。"),
                    List.of(string("file", "file-sample"), string("error", "error-sample"))),
            new Row("upm.start.record.refused", "{0}",
                    List.of("更新记录 %s 已拒绝执行：%s"),
                    List.of(string("file", "file-sample"), string("reason", "reason-sample"))),
            new Row("upm.start.record.unreadable", "{0}",
                    List.of("更新记录 %s 无法读取，已跳过：%s"),
                    List.of(string("file", "file-sample"), string("error", "error-sample"))),
            new Row("upm.start.record.write_failed", "{0}",
                    List.of("更新记录 %s 无法写入：%s"),
                    List.of(string("file", "file-sample"), string("error", "error-sample")))
    );

    private record Row(String path, String pattern, List<String> legacyKeys, List<Arg> args) {
        @Override public String toString() { return path; }
    }

    private record Run(String text, TextColor colour) { }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("translations")
    void everyNewKeyPreservesLegacyTextAndColours(Locale locale, Row row) throws IOException {
        List<LogRecord> warnings = new ArrayList<>();
        String expected = legacyText(readLegacy(locale), row);
        Component actual = render(locale, row.path(), warnings, row.args().toArray(Arg[]::new));
        assertThat(warnings).as("no missing key or format warning for %s", row.path()).isEmpty();
        assertThat(PLAIN.serialize(actual)).isEqualTo(HEX.matcher(expected).replaceAll("").replaceAll("§.", ""));
        assertColourParity(expected, actual);
    }

    @Test
    void inventoryCoversExactly57DistinctKeys() {
        assertThat(ROWS).hasSize(57);
        assertThat(ROWS.stream().map(Row::path).distinct()).hasSize(57);
    }

    @Test
    void bothShippedCataloguesMatchTheUpmConstants() {
        Set<String> constants = Arrays.stream(UpmKeys.class.getFields())
                .filter(field -> field.getType().equals(MessageKey.class))
                .map(field -> {
                    try {
                        return ((MessageKey) field.get(null)).path();
                    } catch (IllegalAccessException failure) {
                        throw new AssertionError(failure);
                    }
                }).collect(Collectors.toSet());
        var english = upmPaths(SHIPPED.shippedFiles().get("en.yml"));
        var chinese = upmPaths(SHIPPED.shippedFiles().get("zh.yml"));
        assertThat(SHIPPED.diagnostics()).isEmpty();
        assertThat(constants).isNotEmpty().containsAll(ROWS.stream().map(Row::path).toList());
        assertUpmPaths(english, chinese, constants);
        assertThat(constants).allSatisfy(path -> {
            assertThat(SHIPPED.catalogue().isDefined(path, Locale.ENGLISH)).isTrue();
            assertThat(SHIPPED.catalogue().isDefined(path, Locale.CHINESE)).isTrue();
        });
    }

    @Test
    void missingKeyIsDetectedEvenWhenSourceLocaleCanSupplyIt() {
        var complete = upmPaths("upm: {first: 'a', second: 'b'}\n");
        var missing = upmPaths("upm: {first: 'a'}\n");
        Set<String> constants = Set.of("upm.first", "upm.second");
        assertUpmPaths(complete, complete, constants);
        assertThatThrownBy(() -> assertUpmPaths(complete, missing, constants))
                .isInstanceOf(AssertionError.class).hasMessageContaining("upm.second");
    }

    @Test
    void commandAndStartArgumentsRemainLiteral() {
        String raw = "<red>x<click:run_command:/op x>y&cz";
        assertLiteral("upm.list.entry.intro", string("description", raw), "   Intro:" + raw,
                TextColor.color(0x7f7f7f));
        assertLiteral("upm.start.record.refused", string("reason", raw),
                "Update record demo.json was refused and not carried out: " + raw, null, string("file", "demo.json"));
    }

    private static void assertLiteral(String path, Arg value, String expected, TextColor colour, Arg... other) {
        List<LogRecord> warnings = new ArrayList<>();
        Arg[] args = Stream.concat(Stream.of(value), Arrays.stream(other)).toArray(Arg[]::new);
        Component actual = render(Locale.ENGLISH, path, warnings, args);
        assertThat(warnings).isEmpty();
        assertThat(PLAIN.serialize(actual)).isEqualTo(expected);
        assertThat(colourRuns(actual)).containsExactly(new Run(expected, colour));
        assertThat(descendants(actual)).allSatisfy(part -> assertThat(part.clickEvent()).isNull());
    }

    private static Stream<Component> descendants(Component node) {
        return Stream.concat(Stream.of(node), node.children().stream().flatMap(UpmLegacyTextParityTest::descendants));
    }

    private static void assertUpmPaths(Set<String> en, Set<String> zh, Set<String> constants) {
        assertThat(en).containsExactlyInAnyOrderElementsOf(constants);
        assertThat(zh).containsExactlyInAnyOrderElementsOf(constants);
    }

    private static Set<String> upmPaths(String source) {
        Map<?, ?> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
        Set<String> paths = new TreeSet<>();
        addLeafPaths("", root, paths);
        return paths.stream().filter(path -> path.startsWith("upm.")).collect(Collectors.toSet());
    }

    private static void addLeafPaths(String prefix, Map<?, ?> mapping, Set<String> paths) {
        mapping.forEach((key, value) -> {
            String path = prefix + key;
            if (value instanceof Map<?, ?> nested) {
                addLeafPaths(path + ".", nested, paths);
            } else {
                paths.add(path);
            }
        });
    }

    private static String legacyText(JsonObject source, Row row) {
        String format = row.pattern();
        for (int i = 0; i < row.legacyKeys().size(); i++) {
            String key = row.legacyKeys().get(i);
            String translation = source.has(key) ? source.get(key).getAsString() : key;
            // The old bundled list components used a newline after the header.
            format = format.replace("{" + i + "}", translation.replaceFirst("\\n$", ""));
        }
        return String.format(Locale.ROOT, format, row.args().stream().map(UpmLegacyTextParityTest::value).toArray());
    }

    private static Object value(Arg arg) {
        return switch (arg) {
            case Arg.Unparsed text -> text.value();
            case Arg.Numeric numeric -> numeric.value();
            default -> throw new AssertionError("Unsupported legacy sample: " + arg);
        };
    }

    private static void assertColourParity(String expected, Component actual) {
        if (expected.startsWith("§")) {
            assertThat(LEGACY.serialize(actual)).isEqualTo(expected);
        } else if (expected.startsWith("<#")) {
            var positions = HEX.matcher(expected).results().toList();
            List<Run> runs = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                var colour = positions.get(i);
                int end = i + 1 < positions.size() ? positions.get(i + 1).start() : expected.length();
                runs.add(new Run(expected.substring(colour.end(), end),
                        TextColor.color(Integer.parseInt(colour.group(1), 16))));
            }
            assertThat(colourRuns(actual)).containsExactlyElementsOf(runs);
        } else {
            assertThat(colourRuns(actual)).containsExactly(new Run(expected, null));
        }
    }

    private static List<Run> colourRuns(Component component) {
        List<Run> runs = new ArrayList<>();
        appendRuns(component, null, runs);
        return runs;
    }

    private static void appendRuns(Component component, TextColor inherited, List<Run> runs) {
        TextColor colour = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            addRun(text.content(), colour, runs);
        }
        component.children().forEach(child -> appendRuns(child, colour, runs));
    }

    private static void addRun(String text, TextColor colour, List<Run> runs) {
        if (!runs.isEmpty() && Objects.equals(runs.getLast().colour(), colour)) {
            Run previous = runs.removeLast();
            runs.add(new Run(previous.text() + text, colour));
        } else {
            runs.add(new Run(text, colour));
        }
    }

    private static Component render(Locale locale, String path, List<LogRecord> warnings, Arg... args) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }
            @Override public void flush() { /* No buffered records. */ }
            @Override public void close() { /* No external resources. */ }
        });
        var renderer = new MessageRenderer(ns -> SHIPPED.catalogue(),
                new FixedLocaleResolver(locale, Optional.empty()), logger);
        return renderer.render(Text.of(MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, path), args), locale);
    }

    private static Stream<Arguments> translations() {
        return Stream.of(Locale.ENGLISH, Locale.CHINESE).flatMap(locale -> ROWS.stream()
                .map(row -> Arguments.of(locale, row)));
    }

    private static JsonObject readLegacy(Locale locale) throws IOException {
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/lang", locale.getLanguage() + ".json"))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
