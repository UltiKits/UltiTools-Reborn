package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

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
