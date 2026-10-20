package com.ultikits.testfixtures.dependprecheck;

import com.ultikits.testfixtures.dependmissing.AbsentEconomyApi;
import com.ultikits.ultitools.annotations.Service;

/**
 * A scanned component implementing a type from the absent required plugin -- the role
 * UltiEconomy's {@code VaultEconomyProvider} plays. Loading it fails with {@link
 * NoClassDefFoundError} while that plugin is missing.
 */
@Service
public class PrecheckProviderService implements AbsentEconomyApi {
}
