package com.ultikits.ultitools.commands;

import static com.ultikits.ultitools.text.Arg.number;
import static com.ultikits.ultitools.text.Arg.string;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.CommandKeys;
import com.ultikits.ultitools.abstracts.command.CommandRuntime;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.entities.UpdateInfo;
import com.ultikits.ultitools.manager.ModuleApiLevels;
import com.ultikits.ultitools.manager.UpdateManager;
import com.ultikits.ultitools.text.Arg;
import com.ultikits.ultitools.text.MessageKey;
import com.ultikits.ultitools.text.Text;
import com.ultikits.ultitools.utils.ModuleContent;
import com.ultikits.ultitools.utils.ModuleFileTransactions.StartOutcome;
import com.ultikits.ultitools.utils.PendingChanges;
import com.ultikits.ultitools.utils.PendingChanges.PendingChange;
import com.ultikits.ultitools.utils.UpmKeys;
import com.ultikits.ultitools.utils.PluginInstallUtils;
import com.ultikits.ultitools.utils.VersionLookup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;

@CmdExecutor(description = "UltiTools Plugin Management Commands", alias = "upm", requireOp = true)
@CmdTarget(CmdTarget.CmdTargetType.BOTH)
public class PluginInstallCommands extends BaseCommandExecutor {

    /**
     * The literal Bukkit {@code name:} prefix {@link #isSameModule} strips before comparing a
     * loaded module's runtime name against the catalogue's display name (#439).
     */
    private static final String VENDOR_PREFIX = "UltiTools-";
    private static final String LIST_SEPARATOR = "---------------------";
    private static final TextColor LIST_CYAN = TextColor.color(0x00ffff);

    @CmdMapping(format = "list <page>")
    @RunAsync
    public void listPlugins(@CmdSender CommandSender sender, @CmdParam("page") String page) {
        int pageInt;
        try {
            pageInt = page == null || page.isEmpty() ? 1 : Integer.parseInt(page);
        } catch (NumberFormatException e) {
            send(sender, CommandKeys.ARGUMENT_ERROR, string("error", "Failed to parse '" + page + "' as Integer"));
            return;
        }
        List<UltiToolsPlugin> installed = UltiTools.getInstance().getPluginManager().getPluginList();
        List<PluginEntity> plugins = PluginInstallUtils.getPluginList(pageInt, 10);
        replyOnServerThread(() -> sendPluginList(sender, pageInt, installed, plugins));
    }

    private static void sendPluginList(CommandSender sender, int page, List<UltiToolsPlugin> installed,
                                       List<PluginEntity> plugins) {
        send(sender, UpmKeys.LIST_HEADER);
        boolean player = sender instanceof Player;
        int index = 1;
        for (PluginEntity plugin : plugins) {
            send(sender, UpmKeys.LIST_ENTRY_NAME, number("index", index), string("name", String.valueOf(plugin.getName())));
            if (player) {
                sendPlayerEntry(sender, plugin, resolveInstalledRuntimeName(installed, plugin));
            } else {
                send(sender, UpmKeys.LIST_ENTRY_INSTALL_COMMAND, string("module", String.valueOf(plugin.getIdentifyString())));
            }
            send(sender, UpmKeys.LIST_ENTRY_INTRO, string("description", String.valueOf(plugin.getShortDescription())));
            if (index < plugins.size()) {
                sender.sendMessage(Component.text(LIST_SEPARATOR, player ? LIST_CYAN : null));
            }
            index++;
        }
        send(sender, UpmKeys.LIST_PAGE, number("page", page));
    }

    private static void sendPlayerEntry(CommandSender sender, PluginEntity plugin, String runtimeName) {
        if (runtimeName == null) {
            send(sender, UpmKeys.LIST_ENTRY_STATUS_NOT_INSTALLED);
            sendListButton(sender, UpmKeys.LIST_ENTRY_INSTALL_BUTTON, UpmKeys.LIST_ENTRY_INSTALL_HOVER,
                    "/upm install " + plugin.getIdentifyString());
        } else {
            send(sender, UpmKeys.LIST_ENTRY_STATUS_INSTALLED);
            // Uninstall matches the loaded module's runtime name, not the catalogue display name.
            sendListButton(sender, UpmKeys.LIST_ENTRY_UNINSTALL_BUTTON, UpmKeys.LIST_ENTRY_UNINSTALL_HOVER,
                    "/upm uninstall " + runtimeName);
        }
    }

    private static void sendListButton(CommandSender sender, MessageKey label, MessageKey hover, String command) {
        replyOnServerThread(() -> {
            var messages = CommandRuntime.messages();
            Component button = messages.render(label, sender).hoverEvent(messages.render(hover, sender))
                    .clickEvent(ClickEvent.runCommand(command));
            sender.sendMessage(button);
        });
    }

    private static void replyOnServerThread(Runnable reply) {
        if (Bukkit.isPrimaryThread()) {
            reply.run();
        } else {
            Bukkit.getScheduler().runTask(UltiTools.getInstance(), reply);
        }
    }

    @CmdMapping(format = "list")
    @RunAsync
    public void listPlugins(@CmdSender CommandSender sender) {
        listPlugins(sender, "1");
    }

    @CmdMapping(format = "install <plugin> <version>")
    @RunAsync
    public void installPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin, @CmdParam("version") String version) {
        sendResult(sender, PluginInstallUtils.installReporting(plugin, version, sender));
    }

    @CmdMapping(format = "install <plugin>")
    @RunAsync
    public void installPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
        sendResult(sender, PluginInstallUtils.installReporting(plugin, null, sender));
    }

    @CmdMapping(format = "versions <plugin>")
    @RunAsync
    public void listVersions(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
        List<String> pluginVersions = new ArrayList<>();
        VersionLookup lookup =
                PluginInstallUtils.lookUpPluginVersions(plugin, pluginVersions);
        if (lookup.getOutcome() == VersionLookup.Outcome.NONE) {
            send(sender, UpmKeys.VERSIONS_NOT_FOUND, string("module", String.valueOf(plugin)));
            return;
        }
        if (lookup.isFailed()) {
            send(sender, UpmKeys.VERSIONS_FAILED);
            return;
        }
        if (pluginVersions.isEmpty()) {
            // An empty successful lookup means that the catalogue has no installable version.
            int level = ModuleApiLevels.frameworkLevel();
            send(sender, UpmKeys.VERSIONS_NONE_INSTALLABLE, string("module", String.valueOf(plugin)),
                    number("level", level), number("floor", ModuleApiLevels.MIN_INSTALLABLE_API_LEVEL), number("max", level));
            return;
        }
        replyOnServerThread(() -> sendVersionList(sender, plugin, pluginVersions));
    }

    private static void sendVersionList(CommandSender sender, String plugin, List<String> versions) {
        send(sender, UpmKeys.VERSIONS_HEADER);
        int index = 1;
        for (String version : versions) {
            send(sender, UpmKeys.VERSIONS_ENTRY, number("index", index++), string("version", String.valueOf(version)));
        }
        send(sender, UpmKeys.VERSIONS_INSTALL_COMMAND, string("module", String.valueOf(plugin)));
        sender.sendMessage(Component.text(LIST_SEPARATOR));
    }

    // Deliberately not @RunAsync, though it opens one archive per entry of the modules folder:
    // A removal request only does local file I/O and hashing, so it stays on the calling thread.
    @CmdMapping(format = "uninstall <plugin>")
    public void uninstallPlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String plugin) {
        sendResult(sender, PluginInstallUtils.requestUninstall(plugin, sender));
    }

    @CmdMapping(format = "pending")
    public void pending(@CmdSender CommandSender sender) {
        var transactions = UltiTools.getInstance().getModuleFileTransactions();
        if (transactions != null) {
            List<StartOutcome> outcomes = transactions.startOutcomes();
            if (!outcomes.isEmpty()) {
                send(sender, UpmKeys.PENDING_LAST_START_HEADER);
                outcomes.forEach(outcome -> sendStartOutcome(sender, outcome));
            }
        }
        var snapshot = PluginInstallUtils.pendingChanges().snapshot();
        if (snapshot.entries().isEmpty()) {
            send(sender, UpmKeys.PENDING_EMPTY);
            return;
        }
        send(sender, UpmKeys.PENDING_HEADER);
        int index = 1;
        for (PendingChange change : snapshot.entries()) {
            sendPendingEntry(sender, change, index++);
        }
        if (snapshot.truncated() > 0) {
            send(sender, UpmKeys.PENDING_TRUNCATED, number("count", snapshot.truncated()));
        }
        send(sender, UpmKeys.PENDING_FOOTER);
    }

    @CmdMapping(format = "revoke <module>")
    public void revoke(@CmdSender CommandSender sender, @CmdParam(value = "module", suggest = "revocableModules") String module) {
        var result = PluginInstallUtils.pendingChanges().revokeByModule(module);
        switch (result.outcome()) {
            case REVOKED -> sendRevoked(sender, result.before());
            case NOT_REVOCABLE -> sendNotRevocable(sender, result.before());
            case NOT_FOUND -> send(sender, UpmKeys.REVOKE_NOT_FOUND, string("module", module));
            case FAILED, CHANGED -> send(sender, UpmKeys.REVOKE_FAILED, string("module", module), arg("error", result.error()));
            default -> throw new IllegalStateException("Unhandled revoke outcome: " + result.outcome());
        }
    }

    private static void sendNotRevocable(CommandSender sender, PendingChange item) {
        Arg module = string("module", item.module());
        switch (item.state()) {
            case FAILED -> send(sender, UpmKeys.REVOKE_NOT_REVOCABLE_FAILED, module, arg("failure", item.failure()));
            case NEEDS_OPERATOR -> send(sender, UpmKeys.REVOKE_NOT_REVOCABLE_NEEDS_OPERATOR, module,
                    arg("failure", item.failure()), recordFile(item));
            case IN_PROGRESS -> send(sender, UpmKeys.REVOKE_NOT_REVOCABLE_IN_PROGRESS, module);
            default -> throw new IllegalStateException("Unhandled non-revocable state: " + item.state());
        }
    }

    public List<String> revocableModules() {
        return PluginInstallUtils.pendingChanges().list().stream().filter(PendingChange::revocable)
                .map(PendingChange::module).distinct().toList();
    }

    private static boolean sendResult(CommandSender sender, PluginInstallUtils.PendingResult result) {
        for (var reply : result.replies()) {
            send(sender, reply.key(), reply.args().toArray(Arg[]::new));
        }
        if (!result.content().isEmpty()) {
            sendWarnings(sender, result.warningModule(), result.content());
            send(sender, UpmKeys.PENDING_WARNING_REVOKE_HINT, string("module", result.warningModule()));
        }
        return result.queued();
    }

    private static void sendWarnings(CommandSender sender, String name, ModuleContent content) {
        Arg module = string("module", name);
        for (var contentKind : content.kinds()) {
            switch (contentKind.kind()) {
                case "enchantment" -> send(sender, UpmKeys.PENDING_WARNING_ENCHANTMENT, module);
                case "datapack" -> send(sender, UpmKeys.PENDING_WARNING_DATAPACK, module);
                case "tag" -> send(sender, UpmKeys.PENDING_WARNING_TAG, module);
                case "unknown" -> send(sender, UpmKeys.PENDING_WARNING_UNKNOWN);
                default -> send(sender, UpmKeys.PENDING_WARNING_OTHER, module, string("kind", contentKind.kind()));
            }
        }
    }

    private static void sendPendingEntry(CommandSender sender, PendingChange item, int index) {
        Arg number = number("index", index);
        Arg module = string("module", item.module());
        Arg from = arg("from", item.fromVersion());
        Arg to = arg("to", item.toVersion());
        switch (item.kind()) {
            case INSTALL -> send(sender, UpmKeys.PENDING_ENTRY_INSTALL, number, module, to);
            case UPDATE -> send(sender, UpmKeys.PENDING_ENTRY_UPDATE, number, module, from, to);
            case UNINSTALL -> send(sender, UpmKeys.PENDING_ENTRY_UNINSTALL, number, module, from,
                    string("files", String.join(", ", item.files())));
            default -> throw new IllegalStateException("Unhandled pending change kind: " + item.kind());
        }
        if (item.requestedAt() != null) {
            String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(item.requestedAt()));
            send(sender, UpmKeys.PENDING_ENTRY_REQUESTED, Arg.text("who", requesterText(item.requestedBy())), string("time", time));
        }
        switch (item.state()) {
            case DOWNLOADING -> send(sender, UpmKeys.PENDING_STATE_DOWNLOADING);
            case FAILED -> send(sender, UpmKeys.PENDING_STATE_FAILED, arg("failure", item.failure()), module);
            case NEEDS_OPERATOR -> send(sender, UpmKeys.PENDING_STATE_NEEDS_OPERATOR,
                    arg("failure", item.failure()), recordFile(item));
            case IN_PROGRESS -> send(sender, UpmKeys.PENDING_STATE_IN_PROGRESS);
            case PENDING -> { /* The entry already says what is pending. */ }
            default -> throw new IllegalStateException("Unhandled pending state: " + item.state());
        }
        sendWarnings(sender, item.module(), item.content());
    }

    private static Text requesterText(PendingChanges.Requester requester) {
        String kind = requester == null ? "" : requester.kind();
        return switch (kind == null ? "" : kind) {
            case "console" -> Text.of(UpmKeys.PENDING_REQUESTER_CONSOLE);
            case "player" -> Text.of(UpmKeys.PENDING_REQUESTER_PLAYER, arg("name", requester.name()));
            case "remote" -> Text.of(UpmKeys.PENDING_REQUESTER_REMOTE, arg("name", requester.name()));
            default -> Text.of(UpmKeys.PENDING_REQUESTER_UNKNOWN);
        };
    }

    private static void sendStartOutcome(CommandSender sender, StartOutcome outcome) {
        MessageKey key = switch (outcome.result()) {
            case APPLIED -> switch (outcome.kind()) {
                case INSTALL -> UpmKeys.PENDING_LAST_START_APPLIED_INSTALL;
                case UPDATE -> UpmKeys.PENDING_LAST_START_APPLIED_UPDATE;
                case UNINSTALL -> UpmKeys.PENDING_LAST_START_APPLIED_UNINSTALL;
            };
            case ROLLED_BACK -> outcome.kind() == PendingChanges.Kind.INSTALL
                    ? UpmKeys.PENDING_LAST_START_ROLLED_BACK_INSTALL : UpmKeys.PENDING_LAST_START_ROLLED_BACK_UPDATE;
            case NOT_APPLIED -> UpmKeys.PENDING_LAST_START_NOT_APPLIED;
            case NEEDS_OPERATOR -> UpmKeys.PENDING_LAST_START_NEEDS_OPERATOR;
        };
        send(sender, key, string("module", outcome.module()), arg("from", outcome.fromVersion()), arg("to", outcome.toVersion()));
    }

    private static void sendRevoked(CommandSender sender, PendingChange before) {
        Arg module = string("module", before.module());
        if (before.state() == PendingChanges.State.DOWNLOADING) {
            send(sender, UpmKeys.REVOKE_DONE_DOWNLOADING, module);
        } else {
            switch (before.kind()) {
                case INSTALL -> send(sender, UpmKeys.REVOKE_DONE_INSTALL, module, arg("to", before.toVersion()));
                case UPDATE -> send(sender, UpmKeys.REVOKE_DONE_UPDATE, module, arg("from", before.fromVersion()),
                        arg("to", before.toVersion()));
                case UNINSTALL -> send(sender, UpmKeys.REVOKE_DONE_UNINSTALL, module);
                default -> throw new IllegalStateException("Unhandled revoked change kind: " + before.kind());
            }
        }
    }

    private static Arg recordFile(PendingChange item) {
        return arg("file", item.recordFile() == null ? null : item.recordFile().getAbsolutePath());
    }

    private static Arg arg(String tag, String value) {
        return string(tag, value == null ? "?" : value);
    }

    private static void send(CommandSender sender, MessageKey key, Arg... args) {
        replyOnServerThread(() -> CommandRuntime.messages().send(sender, key, args));
    }

    @CmdMapping(format = "check")
    public void checkUpdates(@CmdSender CommandSender sender) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (sendPendingIfAny(sender, updateManager)) {
            return;
        }
        if (!updateManager.hasAnyUpdates()) {
            if (!sendIncompleteIfAny(sender, unconfirmed(updateManager))) {
                send(sender, UpmKeys.CHECK_NONE);
            }
            return;
        }
        send(sender, UpmKeys.CHECK_HEADER);
        UpdateInfo fwUpdate = updateManager.getFrameworkUpdate();
        if (fwUpdate != null) {
            send(sender, UpmKeys.CHECK_FRAMEWORK, string("current", String.valueOf(fwUpdate.getCurrentVersion())),
                    string("latest", String.valueOf(fwUpdate.getLatestVersion())));
            send(sender, UpmKeys.CHECK_DOWNLOAD,
                    string("url", "https://github.com/UltiKits/UltiTools-Reborn/releases/latest"));
        }
        for (UpdateInfo info : updateManager.getModuleUpdates().values()) {
            send(sender, UpmKeys.CHECK_MODULE, string("module", String.valueOf(info.getPluginName())),
                    string("current", String.valueOf(info.getCurrentVersion())),
                    string("latest", String.valueOf(info.getLatestVersion())));
        }
        sendIncompleteIfAny(sender, unconfirmed(updateManager));
    }

    /**
     * Commands, like the join listener, read results only after the startup check publishes its
     * completion flag. Before then, even a non-empty result is only a partial scan (#663).
     *
     * @return whether the pending reply was sent instead of consuming unfinished results
     */
    private static boolean sendPendingIfAny(CommandSender sender, UpdateManager updateManager) {
        if (updateManager != null && updateManager.isCheckComplete()) {
            return false;
        }
        if (updateManager != null && updateManager.isCheckFailed()) {
            // #838: a check that died will not complete this run; do not promise that it will.
            send(sender, UpmKeys.CHECK_FAILED);
            return true;
        }
        send(sender, UpmKeys.CHECK_RUNNING);
        return true;
    }

    /**
     * What the start-up check could not confirm (D19-032), or an empty list when there is no
     * update manager yet.
     */
    private static List<String> unconfirmed(UpdateManager updateManager) {
        return updateManager == null ? Collections.emptyList() : updateManager.getUnconfirmed();
    }

    /**
     * Phase 19 D19-032: when a lookup of the start-up check failed, "No updates available." is not
     * known to be true for what it could not confirm; this says so instead, naming it, with the same
     * line the console shows at start-up.
     *
     * @return whether a line was sent
     */
    private static boolean sendIncompleteIfAny(CommandSender sender, List<String> names) {
        if (names.isEmpty()) {
            return false;
        }
        send(sender, UpmKeys.CHECK_INCOMPLETE, string("modules", String.join(", ", names)));
        return true;
    }

    /** {@link #unconfirmed(UpdateManager)} without the framework: {@code /upm update all} updates modules only. */
    private static List<String> unconfirmedModules(UpdateManager updateManager) {
        List<String> modules = new ArrayList<>(unconfirmed(updateManager));
        modules.remove(UpdateManager.FRAMEWORK_NAME);
        return modules;
    }

    @CmdMapping(format = "update <plugin>")
    @RunAsync
    public void updatePlugin(@CmdSender CommandSender sender, @CmdParam("plugin") String pluginName) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if ("all".equalsIgnoreCase(pluginName)) {
            if (!sendPendingIfAny(sender, updateManager)) {
                updateAllPlugins(sender);
            }
            return;
        }
        // A queued change can be refreshed without relying on the unfinished startup scan.
        var journal = UltiTools.getInstance().getModuleFileTransactions();
        String identify = journal == null ? null : journal.pendingIdentifyString(pluginName);
        if (identify == null && sendPendingIfAny(sender, updateManager)) {
            return;
        }
        if (identify == null && updateManager != null) {
            UpdateInfo info = updateManager.getModuleUpdates().get(pluginName);
            if (info != null) {
                identify = info.getIdentifyString();
            }
        }
        if (identify == null) {
            if (updateManager != null && updateManager.getUnconfirmed().contains(pluginName)) {
                sendIncompleteIfAny(sender, Collections.singletonList(pluginName));
            } else {
                send(sender, UpmKeys.UPDATE_NONE);
            }
            return;
        }
        send(sender, UpmKeys.UPDATE_UPDATING, string("module", String.valueOf(pluginName)));
        sendResult(sender, PluginInstallUtils.updateResult(pluginName, PluginInstallUtils.stageUpdate(identify, sender)));
    }

    private void updateAllPlugins(CommandSender sender) {
        UpdateManager updateManager = UltiTools.getInstance().getUpdateManager();
        if (updateManager == null || updateManager.getModuleUpdates().isEmpty()) {
            if (!sendIncompleteIfAny(sender, unconfirmedModules(updateManager))) {
                send(sender, UpmKeys.CHECK_NONE);
            }
            return;
        }
        int staged = 0;
        int notStaged = 0;
        for (UpdateInfo info : updateManager.getModuleUpdates().values()) {
            send(sender, UpmKeys.UPDATE_UPDATING, string("module", String.valueOf(info.getPluginName())));
            if (sendResult(sender, PluginInstallUtils.updateResult(info.getPluginName(),
                    PluginInstallUtils.stageUpdate(info.getIdentifyString(), sender)))) {
                staged++;
            } else {
                notStaged++;
            }
        }
        send(sender, UpmKeys.UPDATE_ALL_DONE, number("staged", staged), number("not_staged", notStaged));
        sendIncompleteIfAny(sender, unconfirmedModules(updateManager));
    }

    /**
     * Decides whether {@code installedPlugin} (a currently loaded module) and {@code
     * catalogueEntry} (a directory entry from the UltiCloud catalogue) refer to the SAME module
     * (#439). The prior check compared {@code installedPlugin.getPluginName()} (the module's
     * Bukkit {@code plugin.yml} {@code name:}) against {@code catalogueEntry.getName()} (the
     * catalogue's own display name) with plain string equality -- correct only when the two
     * strings genuinely agree, which they do not for every module.
     * <p>
     * <b>Preferred key: {@code identifyString}.</b> This is the field {@link
     * PluginInstallUtils} and {@link UpdateManager} already treat
     * as this framework's stable module identity for install/update/version lookups, and the one
     * field {@link PluginEntity} and {@link UltiToolsPlugin} both carry under the same name.
     * Compared only when BOTH sides carry a non-blank value -- two identify-string-less entries
     * are never treated as a match on that basis alone, or every such module would appear
     * installed against every such catalogue entry, which is worse than the bug being fixed.
     * <b>This branch is load-bearing (corrected for 6.4.0, UltiTools-Reborn#658):</b> all fifteen
     * active modules now declare {@code identify-string:} in their {@code plugin.yml}, and the
     * catalogue carries the same identifier, so for every first-party module the installed-state
     * match ({@code /upm list}) is decided here. (When #439 was fixed, as of 6.3.0, none of them
     * declared one and this branch was dormant; the earlier wording of this javadoc said so.)
     * <p>
     * <b>The fallback when either identifier is blank: exact name equality, plus one fixed,
     * deterministic prefix strip.</b> It still decides third-party modules without an
     * {@code identify-string:} and catalogue entries without one. Measured (WR-01): only 4 of the
     * 15 active modules declare a Bukkit {@code name:} carrying the literal {@code "UltiTools-"} prefix -- {@code UltiTools-Chat}
     * ({@code Modules/UltiChat/src/main/resources/plugin.yml:1}), {@code UltiTools-Economy}
     * ({@code Modules/UltiEconomy/.../plugin.yml:1}), {@code UltiTools-Kits}
     * ({@code Modules/UltiKits/.../plugin.yml:1}), and {@code UltiTools-Menu}
     * ({@code Modules/UltiMenu/.../plugin.yml:1}) -- the last two ARE #439's own two named
     * modules. The other 11 modules declare their bare folder name with no prefix at all (e.g.
     * {@code UltiLogin}, {@code UltiWorlds}). This is NOT a declared or tool-enforced convention: {@code
     * Tooling/ultikits-cli/src/lib/templates.ts:152} templates {@code name:} directly from the
     * author-supplied module name with no prefix logic, and {@code
     * Tooling/UltiTools-Maven-Archetype} ships no {@code plugin.yml} template at all -- it is
     * simply each of these 4 authors' own historical choice. Stripping that one fixed prefix
     * before comparing is a single deterministic transform, not a similarity/fuzzy heuristic: it
     * accepts precisely the pairs that differ by nothing, or by exactly that one literal prefix,
     * and rejects every other pair -- including one that merely shares a prefix or substring (a
     * catalogue name that only resembles a loaded module's, such as one ending in {@code "Pro"},
     * is rejected, not matched).
     * <p>
     * <b>Two present identifiers are authoritative, even on a mismatch (gate-2 Codex round 1).</b>
     * When BOTH sides carry a non-blank {@code identifyString}, that comparison alone decides the
     * outcome -- a match returns {@code true} immediately, and a MISMATCH returns {@code false}
     * immediately, without ever falling through to the name/prefix heuristic below. Two present,
     * different stable identifiers prove the modules are different; the name heuristic must not
     * override that proof (a loaded {@code UltiTools-Foo} with id {@code author-a.foo} must not be
     * reported as the catalogue's unrelated {@code Foo} with id {@code author-b.foo}, even though
     * the name/prefix rule alone would match them). The comparison is normalised (trimmed,
     * lower-cased) via the same convention {@code PluginInstallUtils}'s own (private) {@code
     * normalizeIdentifyString} already applies before every install/update/lookup comparison
     * elsewhere in this package, so a module and a catalogue entry that agree except for case are
     * still recognised as the same.
     * <p>
     * <b>Residual collision risk (WR-01), disclosed rather than papered over:</b> the risk above
     * is closed whenever BOTH sides carry an identifier. It remains open only when at least one
     * side's {@code identifyString} is blank -- no longer the case for any first-party module, but
     * possible for a third-party one -- in which case a THIRD-PARTY module that names itself {@code UltiTools-Foo} in its own {@code
     * plugin.yml} would still be reported installed against a different, unrelated author's
     * unaffiliated catalogue entry literally named {@code Foo}, since neither {@code
     * UltiToolsPlugin} nor {@code PluginEntity} exposes anything else (a developer/author ID) this
     * method could cross-check in that case. Still strictly better than the defect being fixed:
     * before #439, EVERY module whose name doesn't already match its catalogue display name
     * byte-for-byte is unconditionally reported not installed, a guaranteed false negative for
     * real, correctly-loaded modules. Closing the remaining gap needs a stable identifier
     * populated on both sides (#474), not a looser name heuristic.
     *
     * @param installedPlugin a currently loaded module
     * @param catalogueEntry  a directory entry from the catalogue
     * @return {@code true} iff the two are judged to refer to the same module
     */
    static boolean isSameModule(UltiToolsPlugin installedPlugin, PluginEntity catalogueEntry) {
        String moduleIdentify = normalizeIdentifyString(installedPlugin.getIdentifyString());
        String catalogueIdentify = normalizeIdentifyString(catalogueEntry.getIdentifyString());
        if (moduleIdentify != null && catalogueIdentify != null) {
            // Both sides carry a stable identifier -- AUTHORITATIVE. Never fall through to the
            // name/prefix heuristic below, even when the two identifiers disagree (gate-2 Codex
            // round 1): a present mismatch proves these are different modules.
            return moduleIdentify.equals(catalogueIdentify);
        }

        String runtimeName = installedPlugin.getPluginName();
        String catalogueName = catalogueEntry.getName();
        if (runtimeName == null || catalogueName == null) {
            return false;
        }
        if (runtimeName.equals(catalogueName)) {
            return true;
        }
        return stripVendorPrefix(runtimeName).equals(catalogueName);
    }

    /**
     * Resolves the runtime name to use for the {@code /upm uninstall} click command in {@link
     * #listPlugins(CommandSender, String)}'s player branch: the LOADED module's own {@code
     * getPluginName()}, not the catalogue entry's display name (gate-2 Codex round 1). {@link
     * PluginInstallUtils#requestUninstall} matches only against the runtime name -- for exactly
     * the display-name-mismatch case {@link #isSameModule} exists to recognise as installed,
     * using the catalogue's display name here would build an uninstall command that silently
     * fails without recording a change.
     *
     * @param installedPlugins every currently-loaded module
     * @param catalogueEntry   the catalogue entry being rendered
     * @return the matched module's own runtime name, or {@code null} if none matches
     */
    static String resolveInstalledRuntimeName(List<UltiToolsPlugin> installedPlugins, PluginEntity catalogueEntry) {
        for (UltiToolsPlugin installedPlugin : installedPlugins) {
            if (isSameModule(installedPlugin, catalogueEntry)) {
                return installedPlugin.getPluginName();
            }
        }
        return null;
    }

    /**
     * Trims and lower-cases an identify string, the same normalisation {@code
     * PluginInstallUtils}'s own (private) {@code normalizeIdentifyString} applies before every
     * install/update/lookup comparison elsewhere in this package (gate-2 Codex round 1) -- kept as
     * a small local copy rather than widening that method's visibility, since the transform
     * itself is a one-line, well-established convention, not shared mutable state.
     *
     * @param value the raw identify string, possibly {@code null} or blank
     * @return the normalised value, or {@code null} if the input was {@code null} or blank
     */
    private static String normalizeIdentifyString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String stripVendorPrefix(String runtimeName) {
        return runtimeName.startsWith(VENDOR_PREFIX)
                ? runtimeName.substring(VENDOR_PREFIX.length())
                : runtimeName;
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        send(sender, UpmKeys.HELP_TITLE);
        send(sender, UpmKeys.HELP_LIST);
        send(sender, UpmKeys.HELP_INSTALL);
        send(sender, UpmKeys.HELP_INSTALL_VERSION);
        send(sender, UpmKeys.HELP_VERSIONS);
        send(sender, UpmKeys.HELP_UNINSTALL);
        send(sender, UpmKeys.HELP_CHECK);
        send(sender, UpmKeys.HELP_UPDATE);
        send(sender, UpmKeys.HELP_UPDATE_ALL);
        send(sender, UpmKeys.HELP_PENDING);
        send(sender, UpmKeys.HELP_REVOKE);
    }
}
