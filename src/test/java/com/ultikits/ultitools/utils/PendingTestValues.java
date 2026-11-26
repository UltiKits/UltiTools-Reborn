package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.text.Arg;

/** Extracts typed message arguments without comparing catalogue internals. */
final class PendingTestValues {
    private PendingTestValues() { }

    static List<Object> values(List<Arg> args) {
        return args.stream().map(arg -> (Object) switch (arg) {
            case Arg.Unparsed value -> value.value();
            case Arg.Numeric value -> value.value();
            case Arg.Moment value -> value.value();
            case Arg.Localized value -> value.value();
        }).toList();
    }

    static File stagedJar(File modules, String name) throws IOException {
        File records = ModuleFileTransactions.transactionsFolder(modules.getParentFile());
        try (var files = Files.walk(records.toPath())) {
            return files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().equals(name))
                    .findFirst().orElseThrow().toFile();
        }
    }

    static PluginInstallUtils.PendingResult queueRemoval(ModuleFileTransactions journal, String module, String key,
                                                        File... files) {
        var identity = new ModuleFileTransactions.RemovedModule(key == null ? List.of() : List.of(key), List.of(module),
                java.util.Arrays.stream(files).map(File::getName).toList());
        return ModuleFileTransactions.underLock(() -> journal.queueRemoval(module, key, null, identity,
                List.of(files), List.of(), null, false));
    }

    /** Seeds a 6.x removal so recovery tests can keep an update and a legacy removal side by side. */
    static File legacyRemoval(File data, String module, File file) throws IOException {
        var removal = new JsonObject();
        removal.addProperty("name", file.getName());
        removal.addProperty("size", Files.size(file.toPath()));
        removal.addProperty("lastModified", Files.getLastModifiedTime(file.toPath()).toMillis());
        removal.addProperty("sha256", ResourceHashSidecar.sha256(file));
        var removals = new JsonArray();
        removals.add(removal);
        var record = new JsonObject();
        record.addProperty("type", "REMOVE");
        record.addProperty("key", module);
        record.addProperty("state", "PENDING");
        record.addProperty("moduleName", module);
        record.add("removals", removals);
        String id = ResourceHashSidecar.sha256(("REMOVE:" + module).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        var dir = Files.createDirectories(ModuleFileTransactions.transactionsFolder(data).toPath());
        return Files.writeString(dir.resolve("remove-" + id + ".json"), record.toString()).toFile();
    }

    record RemovalObservation(PluginInstallUtils.PendingResult request, List<String> files, List<String> undetermined) {
        boolean queued() { return request.queued(); }
    }

    static RemovalObservation queueAndApply(String name) {
        return queueAndApply(name, PluginInstallUtils.DEFAULT_MODULE_CODE_SOURCE);
    }

    static RemovalObservation queueAndApply(String name, Function<UltiToolsPlugin, File> codeSource) {
        var framework = UltiTools.getInstance();
        var manager = framework.getPluginManager();
        var loaded = List.copyOf(manager.getPluginList());
        var request = PluginInstallUtils.requestUninstall(name, null, codeSource);
        assertThat(manager.getPluginList()).containsExactlyElementsOf(loaded);
        var transactions = new ModuleFileTransactions(framework.getDataFolder());
        var files = java.util.Collections.<String>emptyList();
        if (request.queued()) {
            var plan = new PendingChanges(transactions).list().stream()
                    .filter(entry -> entry.kind() == PendingChanges.Kind.UNINSTALL).findFirst().orElseThrow();
            var folder = ModuleFileTransactions.modulesFolder(framework.getDataFolder());
            files = plan.files().stream().map(file -> new File(folder, file).getAbsolutePath()).toList();
            assertThat(files).allSatisfy(file -> assertThat(new File(file)).exists());
            transactions.applyBeforeLoad();
        }
        var undetermined = request.replies().stream().filter(reply -> reply.key().equals(UpmKeys.UNINSTALL_UNDETERMINED))
                .flatMap(reply -> java.util.Arrays.stream(((Arg.Unparsed) reply.args().get(1)).value().split(", "))).toList();
        return new RemovalObservation(request, files, undetermined);
    }

    static List<com.ultikits.ultitools.text.MessageKey> keys(PluginInstallUtils.PendingResult request) {
        return request.replies().stream().map(PluginInstallUtils.PendingReply::key).toList();
    }
}
