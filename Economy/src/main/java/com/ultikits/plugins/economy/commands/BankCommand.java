package com.ultikits.plugins.economy.commands;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

public class BankCommand extends AbstractTabExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        BankService bank = UltiEconomy.getBank();
        Economy vault = UltiEconomy.getVault();
        // 以下是银行的命令
        // /bank create <name>
        // /bank deposit <name> <amount>
        // /bank withdraw <name> <amount>
        // /bank transfer <name> <to> <amount>
        // /bank balance <name>
        // /bank members <name>
        // /bank close <name>
        // /bank list
        // /bank help
        // /bank member <name> add <player>
        // /bank member <name> remove <player>
        if (strings.length == 0) {
            return false;
        }
        switch (strings.length) {
            case 1:
                switch (strings[0]) {
                    case "list":
                        List<AccountEntity> banks = bank.getOwnedAccounts(player.getUniqueId());
                        if (banks.isEmpty()) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("你没有任何银行账户"));
                            return true;
                        }
                        player.sendMessage(UltiEconomy.getInstance().i18n("你的银行账户列表:"));
                        for (AccountEntity bankName : banks) {
                            player.sendMessage(" - " + bankName + " : " + bank.checkAccountBalance(player.getUniqueId(), bankName.getName()));
                        }
                        return true;
                    case "help":
                        sendHelpMessage(player);
                        return true;
                    default:
                        return false;
                }
            case 2:
                switch (strings[0]) {
                    case "create":
                        if (bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你已经有一个名为 %s 的银行账户了"), strings[1]));
                            return true;
                        }
                        if (bank.createAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功创建银行账户 %s"), strings[1]));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("创建银行账户 %s 失败"), strings[1]));
                        }
                        return true;
                    case "close":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        if (bank.closeAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功删除银行账户 %s"), strings[1]));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("删除银行账户 %s 失败"), strings[1]));
                        }
                        return true;
                    case "balance":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的余额为 %s"), strings[1], bank.checkAccountBalance(player.getUniqueId(), strings[1])));
                        return true;
                    case "members":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[0])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        List<UUID> members = bank.getAccountMembers(player.getUniqueId(), strings[1]);
                        if (members.isEmpty()) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 没有任何成员"), strings[1]));
                            return true;
                        }
                        player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的成员列表:"), strings[1]));
                        for (UUID member : members) {
                            player.sendMessage(" - " + Bukkit.getOfflinePlayer(member).getName());
                        }
                        return true;
                    default:
                        return false;
                }
            case 3:

                EconomyResponse response;
                switch (strings[0]) {
                    case "deposit":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        double depositAmount = -1;
                        try {
                            depositAmount = Double.parseDouble(strings[2]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("金额必须为数字"));
                            return true;
                        }
                        if (depositAmount <= 0) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("存款金额必须大于 0"));
                            return true;
                        }
                        response = vault.withdrawPlayer(player, depositAmount);
                        if (response.transactionSuccess()) {
                            bank.addMoneyToAccount(player.getUniqueId(), strings[1], depositAmount);
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功向银行账户 %s 存入 %s"), strings[1], depositAmount));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("向银行账户 %s 存入 %s 失败"), strings[1], depositAmount));
                        }
                        return true;
                    case "withdraw":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        double withdrawAmount = -1;
                        try {
                            withdrawAmount = Double.parseDouble(strings[2]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("金额必须为数字"));
                            return true;
                        }
                        if (withdrawAmount <= 0) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("取款金额必须大于 0"));
                            return true;
                        }
                        boolean reduced = bank.reduceMoneyFromAccount(player.getUniqueId(), strings[1], withdrawAmount);
                        if (reduced) {
                            vault.depositPlayer(player, withdrawAmount);
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功从银行账户 %s 取出 %s"), strings[1], withdrawAmount));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("从银行账户 %s 取出 %s 失败"), strings[1], withdrawAmount));
                        }
                        return true;
                    case "del":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        if (!strings[2].equals("confirm")) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("删除银行账户需要确认, 请在命令后加上 confirm"));
                            return true;
                        }
                        boolean closeAccount = bank.closeAccount(player.getUniqueId(), strings[1]);
                        if (closeAccount) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功删除银行账户 %s"), strings[1]));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("删除银行账户 %s 失败，"), strings[1]));
                            player.sendMessage(UltiEconomy.getInstance().i18n("请确保银行账户没有和其他人共享, 且余额为 0"));
                        }
                        return true;
                    default:
                        return false;
                }
            case 4:
                switch (strings[0]) {
                    case "transfer":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[2])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[2]));
                            return true;
                        }
                        double transferAmount = -1;
                        try {
                            transferAmount = Double.parseDouble(strings[3]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("金额必须为数字"));
                            return true;
                        }
                        if (transferAmount <= 0) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("转账金额必须大于 0"));
                            return true;
                        }
                        AccountEntity fromAccount = bank.getAccountByName(player.getUniqueId(), strings[1]);
                        if (fromAccount.getBalance() < transferAmount) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("银行账户 %s 的余额不足"), strings[1]));
                            return true;
                        }
                        AccountEntity toAccount = bank.getAccountByName(player.getUniqueId(), strings[2]);
                        boolean transfer = bank.accountBalanceTransfer(player.getUniqueId(), fromAccount.getId().toString(), toAccount.getId().toString(), transferAmount);
                        if (transfer) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功从银行账户 %s 向银行账户 %s 转账 %f"), strings[1], strings[2], transferAmount));
                        } else {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("从银行账户 %s 向银行账户 %s 转账 %f 失败"), strings[1], strings[2], transferAmount));
                        }
                        return true;
                    case "member":
                        if (!bank.playerHasAccount(player.getUniqueId(), strings[1])) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("你没有名为 %s 的银行账户"), strings[1]));
                            return true;
                        }
                        if (!strings[2].equals("add") && !strings[2].equals("remove")) {
                            player.sendMessage(UltiEconomy.getInstance().i18n("添加或删除银行账户成员需要指定 add 或 remove"));
                            return true;
                        }
                        OfflinePlayer member = Bukkit.getOfflinePlayer(strings[3]);
                        if (member == null) {
                            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("玩家 %s 不存在"), strings[3]));
                            return true;
                        }
                        switch (strings[2]) {
                            case "add":
                                boolean add = bank.addAccountMember(player.getUniqueId(), strings[1], member.getUniqueId());
                                if (add) {
                                    player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功将玩家 %s 添加到银行账户 %s"), strings[3], strings[1]));
                                } else {
                                    player.sendMessage(String.format(UltiEconomy.getInstance().i18n("将玩家 %s 添加到银行账户 %s 失败"), strings[3], strings[1]));
                                }
                                return true;
                            case "remove":
                                boolean remove = bank.removeAccountMember(player.getUniqueId(), strings[1], member.getUniqueId());
                                if (remove) {
                                    player.sendMessage(String.format(UltiEconomy.getInstance().i18n("成功将玩家 %s 从银行账户 %s 删除"), strings[3], strings[1]));
                                } else {
                                    player.sendMessage(String.format(UltiEconomy.getInstance().i18n("将玩家 %s 从银行账户 %s 删除失败"), strings[3], strings[1]));
                                }
                                return true;
                            default:
                                return false;
                        }
                }
            default:
                return false;
        }
    }

    // 以下是银行的命令
    // /bank create <name>
    // /bank deposit <name> <amount>
    // /bank withdraw <name> <amount>
    // /bank transfer <name> <to> <amount>
    // /bank balance <name>
    // /bank members <name>
    // /bank close <name>
    // /bank list
    // /bank help
    // /bank member <name> add <player>
    // /bank member <name> remove <player>
    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        BankService bank = UltiEconomy.getBank();
        Economy vault = UltiEconomy.getVault();
        switch (strings.length) {
            case 1:
                return Arrays.asList("create", "deposit", "withdraw", "transfer", "balance", "members", "close", "list", "help", "member");
            case 2:
                switch (strings[0]) {
                    case "create":
                        return Collections.singletonList(UltiEconomy.getInstance().i18n("[银行名称]"));
                    case "deposit":
                    case "withdraw":
                    case "transfer":
                    case "balance":
                    case "members":
                    case "close":
                    case "member":
                        List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
                        List<String> accountNames = new ArrayList<>();
                        for (AccountEntity account : accounts) {
                            accountNames.add(account.getName());
                        }
                        return accountNames;
                    case "list":
                    case "help":
                    default:
                        return Collections.emptyList();
                }
            case 3:
                switch (strings[0]) {
                    case "deposit":
                        return Collections.singletonList(
                                String.format(UltiEconomy.getInstance().i18n(
                                        "[数额] 最高 %.2f"), vault.getBalance(player)
                                )
                        );
                    case "withdraw":
                        return Collections.singletonList(
                                String.format(UltiEconomy.getInstance().i18n(
                                                "[数额] 最高 %.2f"),
                                        bank.getAccountByName(player.getUniqueId(), strings[1]).getBalance()
                                )
                        );
                    case "transfer":
                        List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
                        List<String> accountNames = new ArrayList<>();
                        for (AccountEntity account : accounts) {
                            if (!account.getName().equals(strings[1])) {
                                accountNames.add(account.getName());
                            }
                        }
                        return accountNames;
                    case "member":
                        return Arrays.asList("add", "remove");
                    default:
                        return Collections.emptyList();
                }
            case 4:
                switch (strings[0]) {
                    case "transfer":
                        return Collections.singletonList(UltiEconomy.getInstance().i18n("[数额]"));
                    case "member":
                        List<String> players = new ArrayList<>();
                        for (OfflinePlayer player1 : Bukkit.getOfflinePlayers()) {
                            players.add(player1.getName());
                        }
                        return players;
                    default:
                        return Collections.emptyList();
                }
            default:
                return Collections.emptyList();
        }
    }


    @Override
    protected void sendHelpMessage(CommandSender sender) {
        // print all bank accounts commands and usage to player
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
}
