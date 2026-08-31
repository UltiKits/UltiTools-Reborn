package com.ultikits.ultitools.entities;

/**
 * The reason-carrying return type both remote-surface policy predicates change to as of 6.3.0:
 * {@code CommandExecutionManager.isCommandAllowed} and {@code FileOperationManager.isPathAllowed}
 * both previously returned {@code boolean}. Both managers carry {@code @ApiStatus.Internal}, so
 * this signature change is not itself a compatibility event — {@code COMPATIBILITY.md} states that
 * {@code @ApiStatus.Internal} types are not public API — but it is recorded in
 * {@code COMPATIBILITY.md} anyway, because japicmp reads bytecode and does not know
 * {@code @ApiStatus}.
 * <p>
 * Distinguishes a <b>configurable</b> refusal (an operator can flip it — the message names the
 * config key) from a <b>non-configurable</b> one (no configuration changes it — the message says
 * so plainly), per D-17: collapsing the two causes into one sentence sends the operator hunting
 * for a switch that does not exist.
 * <p>
 * Immutable value type shaped like {@code TriggerContext}: private final fields, a private
 * constructor, and named static factories.
 * <p>
 * 命令与文件两个策略判定方法在 6.3.0 起改用的、携带原因的返回类型。区分「可配置」拒绝（操作员可以
 * 通过配置开关改变）与「不可配置」拒绝（没有任何配置能改变它），二者绝不能被合并成同一句话（D-17）。
 *
 * @since 6.3.0
 */
public final class AccessDecision {

    private final boolean allowed;
    private final String reason;
    private final boolean configurable;
    private final String configKey;

    private AccessDecision(boolean allowed, String reason, boolean configurable, String configKey) {
        this.allowed = allowed;
        this.reason = reason;
        this.configurable = configurable;
        this.configKey = configKey;
    }

    /**
     * The allowed decision — no reason, no config key.
     *
     * @return an allowed {@link AccessDecision}
     */
    public static AccessDecision allowed() {
        return new AccessDecision(true, null, false, null);
    }

    /**
     * A denied decision an operator can flip through configuration.
     *
     * @param reason    why access was refused
     * @param configKey the full dotted config path that would flip this refusal — never blank
     * @return a denied, configurable {@link AccessDecision}
     * @throws IllegalArgumentException if {@code configKey} is {@code null} or blank — a
     *                                   configurable refusal naming no key is exactly the silent
     *                                   shape D-17 exists to remove
     */
    public static AccessDecision deniedConfigurable(String reason, String configKey) {
        if (configKey == null || configKey.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "deniedConfigurable requires a non-blank configKey — a configurable refusal "
                            + "naming no key is the silent shape D-17 exists to remove");
        }
        return new AccessDecision(false, reason, true, configKey);
    }

    /**
     * A denied decision no configuration can lift.
     *
     * @param reason why access was refused
     * @return a denied, non-configurable {@link AccessDecision}
     */
    public static AccessDecision deniedNonConfigurable(String reason) {
        return new AccessDecision(false, reason, false, null);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public boolean isConfigurable() {
        return configurable;
    }

    public String getConfigKey() {
        return configKey;
    }

    /**
     * The human-readable refusal message — empty when allowed. Routes both denied shapes through
     * {@link Capability}'s two shared refusal statics rather than formatting a sentence of its own
     * — this is what keeps the whole phase to one refusal vocabulary instead of three managers each
     * formatting their own (D-05, D-13, D-17).
     *
     * @return the empty string when allowed; otherwise the refusal sentence
     */
    public String getMessage() {
        if (allowed) {
            return "";
        }
        if (configurable) {
            return reason + " — " + Capability.configurableRefusal(configKey);
        }
        return Capability.nonConfigurableRefusal(reason);
    }
}
