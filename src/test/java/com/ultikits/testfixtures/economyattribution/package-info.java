/**
 * Fixtures for {@code EconomyUtilsAttributionTest} (#462, #483, #489). Economy attribution reads
 * the live call stack, so each economy request must come from a class in the package being
 * attributed; every sub-package holds exactly one caller (and at most one module or plugin class),
 * so no fixture's package can match another fixture's frames.
 */
package com.ultikits.testfixtures.economyattribution;
