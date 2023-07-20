package com.ultikits.plugins.economy.impls;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;

/**
 * UltiEconomy PlaceHolderAPI变量
 */
public class UltiEconomyExpansion extends PlaceholderExpansion {
    @Override
    public String getIdentifier() {
        return "ue";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        }
//        if (params.contains("leaderboard")){
//            try {
//                int position = Integer.parseInt(params.split("_")[1]);
//                Map.Entry<String, Double> entry = LeaderBoardTask.getPlayer(position);
//                if (entry == null){
//                    return null;
//                }
//                return String.format("%s: %.2f", entry.getKey(), entry.getValue());
//            }catch (Exception e){
//                return null;
//            }
//        }
        Economy economy = UltiEconomy.getVault();
        BankService bankService = UltiEconomy.getBank();
        switch (params) {
            case "money":
                return String.format("%.2f", economy.getBalance(player));
            case "bank_total":
                return String.format("%.2f", bankService.getAccounts(player.getUniqueId()).stream().mapToDouble(AccountEntity::getBalance).sum());
            default:
                return null;
        }
    }
}
