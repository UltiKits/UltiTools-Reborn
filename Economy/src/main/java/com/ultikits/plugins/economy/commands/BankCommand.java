package com.ultikits.plugins.economy.commands;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@CmdExecutor(description = "Bank", alias = {"bank"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class BankCommand extends AbstractCommendExecutor {
    @Autowired
    private BankService bank;
    @Autowired
    private Economy vault;

    @CmdMapping(format = "list")
    public void listAccounts(@CmdSender Player player) {
        List<AccountEntity> banks = bank.getOwnedAccounts(player.getUniqueId());
        if (banks.isEmpty()) {
            player.sendMessage(UltiEconomy.getInstance().i18n("你没有任何银行账户"));
        }
        player.sendMessage(UltiEconomy.getInstance().i18n("你的银行账户列表:"));
        for (AccountEntity bankName : banks) {
            player.sendMessage(" - " + bankName + " : " + bank.checkAccountBalance(player.getUniqueId(), bankName.getName()));
        }
    }

    @CmdMapping(format = "create <name>")
    public void createAccount(@CmdSender Player player,
                              @CmdParam(value = "name", suggest = "[银行名称]") String name) {
        if (bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你已经有一个名为 %s 的银行账户了"), name));
            return;
        }
        if (bank.createAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功创建银行账户 %s"), name));
        } else {
            player.sendMessage(UltiEconomy.getInstance().i18n("创建银行账户 %s 失败"));
        }
    }

    @CmdMapping(format = "balance <name>")
    public void showBalance(@CmdSender Player player,
                            @CmdParam(value = "name", suggest = "getBankList") String name) {
        if (bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的余额为 %s"), name, bank.checkAccountBalance(player.getUniqueId(), name)));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
        }
    }

    @CmdMapping(format = "members <name>")
    public void showMembers(@CmdSender Player player,
                            @CmdParam(value = "name", suggest = "getBankList") String name) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        List<UUID> members = bank.getAccountMembers(player.getUniqueId(), name);
        if (members.isEmpty()) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 没有任何成员"), name));
            return;
        }
        player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的成员列表:"), name));
        for (UUID member : members) {
            player.sendMessage(" - " + Bukkit.getOfflinePlayer(member).getName());
        }
    }

    @CmdMapping(format = "close <name>")
    public void closeAccount(@CmdSender Player player,
                             @CmdParam(value = "name", suggest = "getBankList") String name) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        player.sendMessage(UltiEconomy.getInstance().i18n("删除银行账户需要确认, 请在命令后加上 confirm"));
    }

    @CmdMapping(format = "close <name> confirm")
    public void closeAccountConfirm(@CmdSender Player player,
                                    @CmdParam(value = "name", suggest = "getBankList") String name) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        if (bank.closeAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功删除银行账户 %s"), name));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("删除银行账户 %s 失败"), name));
            player.sendMessage(UltiEconomy.getInstance().i18n("请确保银行账户没有和其他人共享, 且余额为 0"));
        }
    }

    @CmdMapping(format = "deposit <name> <amount>")
    public void deposit(@CmdSender Player player,
                        @CmdParam(value = "name", suggest = "getBankList") String name,
                        @CmdParam(value = "amount", suggest = "getMaxDeposit") double amount) {
        EconomyResponse response;
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        if (amount <= 0) {
            player.sendMessage(UltiEconomy.getInstance().i18n("存款金额必须大于 0"));
            return;
        }
        response = vault.withdrawPlayer(player, amount);
        if (response.transactionSuccess()) {
            bank.addMoneyToAccount(player.getUniqueId(), name, amount);
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功向银行账户 %s 存入 %s"), name, amount));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("向银行账户 %s 存入 %s 失败"), name, amount));
        }
    }

    @CmdMapping(format = "transfer <name> <to> <amount>")
    public void transfer(
            @CmdSender Player player,
            @CmdParam(value = "name", suggest = "getBankList") String name,
            @CmdParam(value = "to", suggest = "getBankList") String to,
            @CmdParam(value = "amount", suggest = "[数额]") double amount
    ) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        if (!bank.playerHasAccount(player.getUniqueId(), to)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        if (amount <= 0) {
            player.sendMessage(UltiEconomy.getInstance().i18n("转账金额必须大于 0"));
            return;
        }
        AccountEntity fromAccount = bank.getAccountByName(player.getUniqueId(), name);
        if (fromAccount.getBalance() < amount) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的余额不足"), name));
            return;
        }
        AccountEntity toAccount = bank.getAccountByName(player.getUniqueId(), to);
        boolean transfer = bank.accountBalanceTransfer(player.getUniqueId(), fromAccount.getId().toString(), toAccount.getId().toString(), amount);
        if (transfer) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功从银行账户 %s 向银行账户 %s 转账 %f"), name, to, amount));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("从银行账户 %s 向银行账户 %s 转账 %f 失败"), name, to, amount));
        }
    }

    @CmdMapping(format = "withdraw <name> <amount>")
    public void withdraw(@CmdSender Player player,
                         @CmdParam(value = "name", suggest = "getBankList") String name,
                         @CmdParam(value = "amount", suggest = "getMaxWithdraw") double amount) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        if (amount <= 0) {
            player.sendMessage(UltiEconomy.getInstance().i18n("取款金额必须大于 0"));
            return;
        }
        boolean reduced = bank.reduceMoneyFromAccount(player.getUniqueId(), name, amount);
        if (reduced) {
            vault.depositPlayer(player, amount);
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功从银行账户 %s 取出 %s"), name, amount));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("从银行账户 %s 取出 %s 失败"), name, amount));
        }
    }

    @CmdMapping(format = "member <name> add <player>")
    public void addMember(
            @CmdSender Player player,
            @CmdParam(value = "name", suggest = "getBankList") String name,
            @CmdParam(value = "player", suggest = "getPlayers") OfflinePlayer target
    ) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        boolean add = bank.addAccountMember(player.getUniqueId(), name, target.getUniqueId());
        if (add) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功将玩家 %s 添加到银行账户 %s"), target.getName(), name));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("将玩家 %s 添加到银行账户 %s 失败"), target.getName(), name));
        }
    }

    @CmdMapping(format = "member <name> remove <player>")
    public void removeMember(
            @CmdSender Player player,
            @CmdParam(value = "name", suggest = "getBankList") String name,
            @CmdParam(value = "player", suggest = "getPlayers") OfflinePlayer target
    ) {
        if (!bank.playerHasAccount(player.getUniqueId(), name)) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), name));
            return;
        }
        boolean remove = bank.removeAccountMember(player.getUniqueId(), name, target.getUniqueId());
        if (remove) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功将玩家 %s 从银行账户 %s 中删除"), target.getName(), name));
        } else {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("将玩家 %s 从银行账户 %s 中删除失败"), target.getName(), name));
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
        sender.sendMessage(UltiEconomy.getInstance().i18n("银行账户命令："));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank create <name> - 创建一个新的银行账户"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank deposit <name> <amount> - 向银行账户存入钱"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank withdraw <name> <amount> - 从银行账户取出钱"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank transfer <name> <to> <amount> - 将银行账户的钱转移到另一个银行账户"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank balance <name> - 查看银行账户的余额"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank members <name> - 查看银行账户的成员"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank close <name> - 关闭银行账户"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank list - 查看所有银行账户"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank help - 查看帮助"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank member <name> add <player> - 向银行账户添加成员"));
        sender.sendMessage(UltiEconomy.getInstance().i18n("/bank member <name> remove <player> - 从银行账户删除成员"));
    }

    public List<String> getBankList(Player player) {
        List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
        List<String> accountNames = new ArrayList<>();
        for (AccountEntity account : accounts) {
            accountNames.add(account.getName());
        }
        return accountNames;
    }

    public List<String> getMaxDeposit(Player player) {
        return Collections.singletonList(
                String.format(UltiEconomy.getInstance().i18n(
                        "[数额] 最高 %.2f"), vault.getBalance(player)
                )
        );
    }

    public List<String> getMaxWithdraw(Player player, String[] strings) {
        return Collections.singletonList(
                String.format(UltiEconomy.getInstance().i18n(
                                "[数额] 最高 %.2f"),
                        bank.getAccountByName(player.getUniqueId(), strings[1]).getBalance()
                )
        );
    }

    public List<String> getPlayers() {
        List<String> players = new ArrayList<>();
        for (OfflinePlayer player1 : Bukkit.getOfflinePlayers()) {
            players.add(player1.getName());
        }
        return players;
    }
}
