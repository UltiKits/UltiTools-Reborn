package com.ultikits.plugins.economy.impls;

import com.ultikits.plugins.economy.UltiEconomy;
import com.ultikits.plugins.economy.apis.BankService;
import com.ultikits.plugins.economy.entity.AccountEntity;
import com.ultikits.plugins.economy.entity.AccountPlayerEntity;
import com.ultikits.ultitools.entities.WhereCondition;
import com.ultikits.ultitools.interfaces.DataOperator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class BankServiceImpl implements BankService {

    @Override
    public String getName() {
        return "Economy-Bank-Service";
    }

    @Override
    public String getAuthor() {
        return "wisdomme";
    }

    @Override
    public int getVersion() {
        return 100;
    }

    @Override
    public boolean playerHasAccount(UUID player, String name) {
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        return dataOperator.exist(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build());
    }

    @Override
    public boolean accountExists(String accountId) {
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        return dataOperator.exist(WhereCondition.builder().column("id").value(accountId).build());
    }

    @Override
    public boolean createAccount(UUID player, String name) {
        if (playerHasAccount(player, name)) {
            return false;
        }
        try {
            DataOperator<AccountEntity> accountEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
            AccountEntity accountEntity = AccountEntity.builder()
                    .balance(0.0)
                    .name(name)
                    .owner(player.toString())
                    .build();
            accountEntity.setId(UUID.randomUUID().toString());
            accountEntityDataOperator.insert(accountEntity);
            AccountPlayerEntity accountPlayerEntity = AccountPlayerEntity.builder()
                    .accountId(accountEntity.getId().toString())
                    .playerId(player.toString())
                    .build();
            accountPlayerEntity.setId(UUID.randomUUID().toString());
            UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class).insert(accountPlayerEntity);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public List<AccountEntity> getAccounts(UUID player) {
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        Collection<AccountPlayerEntity> accountPlayerEntities = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class)
                .getAll(WhereCondition.builder().column("playerId").value(player.toString()).build());
        List<AccountEntity> accountEntities = new ArrayList<>();
        for (AccountPlayerEntity accountPlayerEntity : accountPlayerEntities) {
            AccountEntity byId = dataOperator.getById(accountPlayerEntity.getAccountId());
            if (byId == null) {
                continue;
            }
            accountEntities.add(byId);
        }
        return accountEntities;
    }

    @Override
    public List<AccountEntity> getOwnedAccounts(UUID player) {
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        return (List<AccountEntity>) dataOperator.getAll(WhereCondition.builder().column("owner").value(player.toString()).build());
    }

    @Override
    public boolean closeAccount(UUID player, String name) {
        // find the account and check if there is any record in AccountPlayerEntity table, if there is any record,
        // return false, if there is no record, delete the account and return true
        // if the account is not 0, return false
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = getAccountByName(player, name);
        if (accountEntity == null) {
            return false;
        }
        if (accountEntity.getBalance() != 0) {
            return false;
        }
        DataOperator<AccountPlayerEntity> accountPlayerEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class);
        if (accountPlayerEntityDataOperator.exist(WhereCondition.builder().column("accountId").value(accountEntity.getId()).build())) {
            return false;
        }
        dataOperator.delById(accountEntity.getId());
        return true;
    }

    @Override
    public AccountEntity getAccountByName(UUID player, String name) {
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        return dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
    }

    @Override
    public double checkAccountBalance(UUID player, String name) {
        // check account balance by player and account name.
        // If the account does not exist, return -1. If the account exists, return the balance
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return -1;
        }
        return accountEntity.getBalance();
    }

    @Override
    public boolean addAccountMember(UUID player, String name, UUID member) {
        // add the player to the account as a member
        // if the player is already a member, return false
        // if the player is the owner, return false
        // if the player is not the owner, add the player to the account and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return false;
        }
        if (accountEntity.getOwner().equals(member.toString())) {
            return false;
        }
        DataOperator<AccountPlayerEntity> accountPlayerEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class);
        if (accountPlayerEntityDataOperator.exist(WhereCondition.builder().column("accountId").value(accountEntity.getId()).build(),
                WhereCondition.builder().column("playerId").value(member.toString()).build())) {
            return false;
        }
        accountPlayerEntityDataOperator.insert(AccountPlayerEntity.builder()
                .accountId(accountEntity.getId().toString())
                .playerId(member.toString())
                .build());
        return true;
    }

    @Override
    public boolean removeAccountMember(UUID player, String name, UUID member) {
        // remove the player from the account as a member
        // if the player is not a member, return false
        // if the player is the owner, return false
        // if the player is not the owner, remove the player from the account and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return false;
        }
        if (accountEntity.getOwner().equals(member.toString())) {
            return false;
        }
        DataOperator<AccountPlayerEntity> accountPlayerEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class);
        if (!accountPlayerEntityDataOperator.exist(WhereCondition.builder().column("accountId").value(accountEntity.getId()).build(),
                WhereCondition.builder().column("playerId").value(member.toString()).build())) {
            return false;
        }
        accountPlayerEntityDataOperator.del(WhereCondition.builder().column("accountId").value(accountEntity.getId()).build(),
                WhereCondition.builder().column("playerId").value(member.toString()).build());
        return true;
    }

    @Override
    public List<UUID> getAccountMembers(String accountId) {
        // get all members of the account
        DataOperator<AccountPlayerEntity> accountPlayerEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class);
        List<AccountPlayerEntity> accountPlayerEntities = (List<AccountPlayerEntity>) accountPlayerEntityDataOperator.getAll(
                WhereCondition.builder().column("accountId").value(accountId).build()
        );
        List<UUID> members = new ArrayList<>();
        accountPlayerEntities.forEach(accountPlayerEntity -> members.add(UUID.fromString(accountPlayerEntity.getPlayerId())));
        return members;
    }

    @Override
    public List<UUID> getAccountMembers(UUID player, String name) {
        // get all members of the account
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return null;
        }
        return getAccountMembers(accountEntity.getId().toString());
    }

    @Override
    public boolean isAccountMember(String accountId, UUID player) {
        // check if the player is a member of the account
        DataOperator<AccountPlayerEntity> accountPlayerEntityDataOperator = UltiEconomy.getInstance().getDataOperator(AccountPlayerEntity.class);
        return accountPlayerEntityDataOperator.exist(WhereCondition.builder().column("accountId").value(accountId).build(),
                WhereCondition.builder().column("playerId").value(player.toString()).build());
    }

    @Override
    public boolean isAccountOwner(String accountId, UUID player) {
        // check if the player is the owner of the account
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getById(accountId);
        return accountEntity.getOwner().equals(player.toString());
    }

    @Override
    public boolean accountBalanceTransfer(UUID player, String fromAccountId, String toAccountId, double amount) {
        // transfer money from one account to another
        // if the player is not the owner of the fromAccountId, return false
        // if the fromAccountId does not have enough money, return false
        // if the fromAccountId is the same as toAccountId, return false
        // if the fromAccountId has enough money, transfer the money and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity fromAccountEntity = dataOperator.getById(fromAccountId);
        if (!fromAccountEntity.getOwner().equals(player.toString())) {
            return false;
        }
        if (fromAccountEntity.getBalance() < amount) {
            return false;
        }
        if (fromAccountId.equals(toAccountId)) {
            return false;
        }
        AccountEntity toAccountEntity = dataOperator.getById(toAccountId);
        fromAccountEntity.setBalance(fromAccountEntity.getBalance() - amount);
        toAccountEntity.setBalance(toAccountEntity.getBalance() + amount);
        try {
            dataOperator.update(fromAccountEntity);
            dataOperator.update(toAccountEntity);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean reduceMoneyFromAccount(UUID player, String name, double amount) {
        // reduce money from the account
        // if the player is not the owner of the account, return false
        // if the account does not have enough money, return false
        // if the account has enough money, reduce the money and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return false;
        }
        if (accountEntity.getBalance() < amount) {
            return false;
        }
        accountEntity.setBalance(accountEntity.getBalance() - amount);
        try {
            dataOperator.update(accountEntity);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean addMoneyToAccount(UUID player, String name, double amount) {
        // add money to the account
        // if the player is not the owner of the account, return false
        // if the account has enough money, add the money and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getAll(WhereCondition.builder().column("name").value(name).build(),
                WhereCondition.builder().column("owner").value(player.toString()).build()).stream().findFirst().orElse(null);
        if (accountEntity == null) {
            return false;
        }
        accountEntity.setBalance(accountEntity.getBalance() + amount);
        try {
            dataOperator.update(accountEntity);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    public boolean addMoneyToAccount(String accountId, double amount) {
        // add money to the account
        // if the player is not the owner of the account, return false
        // if the account has enough money, add the money and return true
        DataOperator<AccountEntity> dataOperator = UltiEconomy.getInstance().getDataOperator(AccountEntity.class);
        AccountEntity accountEntity = dataOperator.getById(accountId);
        if (accountEntity == null) {
            return false;
        }
        accountEntity.setBalance(accountEntity.getBalance() + amount);
        try {
            dataOperator.update(accountEntity);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
