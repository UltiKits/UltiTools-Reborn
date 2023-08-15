package com.ultikits.plugins.economy.apis;

import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

/**
 * The interface Bank service.
 */
public interface BankService extends Registrable {

    boolean playerHasAccount(UUID player, String name);

    boolean accountExists(String accountId);

    boolean createAccount(UUID player, String name);

    List<AccountEntity> getAccounts(UUID player);

    List<AccountEntity> getOwnedAccounts(UUID player);

    boolean closeAccount(UUID player, String name);

    AccountEntity getAccountByName(UUID player, String name);

    double checkAccountBalance(UUID player, String name);

    boolean addAccountMember(UUID player, String name, UUID member);

    boolean removeAccountMember(UUID player, String name, UUID member);

    List<UUID> getAccountMembers(String accountId);

    List<UUID> getAccountMembers(UUID player, String name);

    boolean isAccountMember(String accountId, UUID player);

    boolean isAccountOwner(String accountId, UUID player);

    boolean accountBalanceTransfer(UUID player, String fromAccountId, String toAccountId, double amount);

    boolean reduceMoneyFromAccount(UUID player, String name, double amount);

    boolean addMoneyToAccount(UUID player, String name, double amount);

    boolean addMoneyToAccount(String accountId, double amount);

}
