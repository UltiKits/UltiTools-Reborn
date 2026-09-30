package com.ultikits.testfixtures.dependmissing;

/**
 * Stands in for a type that belongs to a required plugin which is not installed (Vault's
 * {@code Economy} for UltiEconomy). The test's class loader refuses to load it.
 */
public interface AbsentEconomyApi {
}
