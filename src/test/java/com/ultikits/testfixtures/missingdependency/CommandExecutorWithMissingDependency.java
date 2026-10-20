package com.ultikits.testfixtures.missingdependency;

import com.ultikits.ultitools.annotations.Autowired;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * A {@link CommandExecutor}-shaped bean whose OWN method signatures are clean -- the failure is one
 * hop away, in its sole {@code @Autowired} field. Mirrors the real shape 07-22's real-server
 * re-run measured for {@code UltiBackup}'s {@code BackupCommand}/{@code BackupService} pair
 * ({@code 07-UAT-CRITERION-1.md}, "Why UltiBackup's poisoned bean is not the class that directly
 * references AbstractDataEntity"): {@code BackupCommand} itself scans and links fine, but its
 * {@code @Autowired BackupService} field fails inside {@code AutowireFactory.autowireBean} because
 * {@code BackupService}'s own method signatures reference a removed symbol.
 * <p>
 * Here, {@link #dependency}'s declared type, {@link HasMethodReferencingMissingType}, plays
 * {@code BackupService}'s role -- when loaded through a class loader that hides
 * {@link MissingDependencyType}, resolving that field throws {@link NoClassDefFoundError} one hop
 * away from this class's own, otherwise-clean, construction.
 * <br>
 * 一个 {@link CommandExecutor} 形态的 Bean，其自身方法签名是干净的——真正的失败发生在一跳之外，
 * 也就是它唯一的 {@code @Autowired} 字段上。对应 07-22 真机复测中测得的 {@code UltiBackup} 模块
 * {@code BackupCommand}/{@code BackupService} 组合的真实形态：{@code BackupCommand} 本身扫描、
 * 链接都正常，但它的 {@code @Autowired BackupService} 字段会在 {@code AutowireFactory.autowireBean}
 * 中失败，因为 {@code BackupService} 自身的方法签名引用了一个已被移除的符号。
 *
 * @see HasMethodReferencingMissingType
 * @see MissingDependencyType
 */
public class CommandExecutorWithMissingDependency implements CommandExecutor {

    @Autowired
    private HasMethodReferencingMissingType dependency;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // No Bukkit runtime is ever invoked in the test that exercises this fixture -- only the
        // method signature (which references nothing from the missingdependency package) matters.
        return false;
    }
}
