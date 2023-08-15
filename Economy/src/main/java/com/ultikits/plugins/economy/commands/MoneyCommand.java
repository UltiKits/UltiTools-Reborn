package com.ultikits.plugins.economy.commands;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.ultitools.abstracts.AbstractTabExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class MoneyCommand extends AbstractTabExecutor {
    @Override
    protected boolean onPlayerCommand(Command command, String[] strings, Player player) {
        // print player's balance details and accounts' details
        double balance = UltiEconomy.getVault().getBalance(player);
        BankService bank = UltiEconomy.getBank();
        List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
        player.sendMessage(String.format(UltiEconomy.getInstance().i18n("现金: %.2f"), balance));
        player.sendMessage(UltiEconomy.getInstance().i18n("你的账户:"));
        for (AccountEntity account : accounts) {
            player.sendMessage(String.format(UltiEconomy.getInstance().i18n("  %s: %.2f"), account.getName(), account.getBalance()));
        }
        return true;
    }

    @Override
    protected List<String> onPlayerTabComplete(Command command, String[] strings, Player player) {
        return null;
    }

    @Override
    protected void sendHelpMessage(CommandSender sender) {

    }
}
