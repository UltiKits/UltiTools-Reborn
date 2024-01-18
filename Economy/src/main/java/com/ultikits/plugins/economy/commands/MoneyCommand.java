package com.ultikits.plugins.economy.commands;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.ultitools.abstracts.AbstractCommendExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@CmdExecutor(description = "Money", alias = {"money"})
@CmdTarget(CmdTarget.CmdTargetType.PLAYER)
public class MoneyCommand extends AbstractCommendExecutor {
    @Autowired
    private BankService bank;
    @Autowired
    private Economy vault;

    @CmdMapping(format = "")
    public void money(@CmdSender Player player) {
        double balance = vault.getBalance(player);
        List<AccountEntity> accounts = bank.getAccounts(player.getUniqueId());
        player.sendMessage(String.format(UltiEconomy.getInstance().i18n("现金: %.2f"), balance));
        player.sendMessage(UltiEconomy.getInstance().i18n("你的账户:"));
        for (AccountEntity account : accounts) {
            player.sendMessage(
                    String.format(
                            UltiEconomy.getInstance().i18n("  %s: %.2f"),
                            account.getName(),
                            account.getBalance()
                    )
            );
        }
    }

    @Override
    protected void handleHelp(CommandSender sender) {
    }
}
