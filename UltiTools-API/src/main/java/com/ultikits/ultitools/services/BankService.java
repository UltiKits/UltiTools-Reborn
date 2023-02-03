package com.ultikits.ultitools.services;

import com.ultikits.ultitools.interfaces.Registrable;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * The interface Bank service.
 */
public interface BankService extends Registrable {
    /**
     * If bank exists.
     *
     * @param player the player
     * @param name   the bank name
     * @return boolean
     */
    boolean bankExists(OfflinePlayer player, String name);

    /**
     * Create bank.
     *
     * @param player the player
     * @param name   the bank name
     * @return the boolean
     */
    boolean createBank(OfflinePlayer player, String name);

    /**
     * Check bank savings.
     *
     * @param player the player
     * @param name   the bank name
     * @return the double
     */
    double checkBank(OfflinePlayer player, String name);

    /**
     * Deposit to a bank.
     *
     * @param player the player
     * @param name   the bank name
     * @param amount the amount
     * @return the boolean
     */
    boolean deposit(OfflinePlayer player, String name, double amount);

    /**
     * Withdraw from a bank.
     *
     * @param player the player
     * @param name   the bank name
     * @param amount the amount
     * @return the boolean
     */
    boolean withdraw(OfflinePlayer player, String name, double amount);

    /**
     * Is the player an owner of a bank.
     *
     * @param player the player
     * @param name   the bank name
     * @return the boolean
     */
    boolean isOwner(OfflinePlayer player, String name);

    /**
     * Is the player a member of a bank.
     *
     * @param player the player
     * @param name   the bank name
     * @return the boolean
     */
    boolean isMember(OfflinePlayer player, String name);

    /**
     * If bank has this amount of savings.
     *
     * @param player the player
     * @param name   the bank name
     * @param amount the amount
     * @return the boolean
     */
    boolean bankHas(OfflinePlayer player, String name, double amount);

    /**
     * Gets bank member.
     *
     * @param player the player
     * @param name   the bank name
     * @return the bank member
     */
    List<String> getBankMember(OfflinePlayer player, String name);

    /**
     * Gets owned banks.
     *
     * @param player the player
     * @return the owned banks
     */
    List<String> getOwnedBanks(OfflinePlayer player);

    /**
     * Gets involved banks.
     *
     * @param player the player
     * @return the involved banks
     */
    List<String> getInvolvedBanks(OfflinePlayer player);

    /**
     * Add bank member.
     *
     * @param owner    the owner
     * @param bankName the bank name
     * @param member   the member
     */
    void addBankMember(OfflinePlayer owner, String bankName, OfflinePlayer member);

    /**
     * Remove bank member.
     *
     * @param owner    the owner
     * @param bankName the bank name
     * @param member   the member
     */
    void removeBankMember(OfflinePlayer owner, String bankName, OfflinePlayer member);

    /**
     * Freeze bank.
     *
     * @param player the player
     * @param name   the bank name
     * @return the boolean
     */
    boolean freezeBank(OfflinePlayer player, String name);

    /**
     * Sets interests.
     *
     * @param player the player
     * @param name   the bank name
     */
    void settleInterests(OfflinePlayer player, String name);

    /**
     * Change primary account.
     *
     * @param player the player
     * @param name   the bank name
     */
    void changePrimaryAccount(OfflinePlayer player, String name);
}
