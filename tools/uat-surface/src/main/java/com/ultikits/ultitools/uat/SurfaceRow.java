package com.ultikits.ultitools.uat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of a module's UAT surface: everything the extractor learned about one executable unit
 * (a command mapping, a bare-command help handler, and — in later Phase 10 plans — other row
 * kinds) from its compiled bytecode.
 * <p>
 * Field naming is this plan's discretion beyond the keys {@code id}/{@code kind}/{@code origin}
 * D-10-04/D-10-08 lock; see the plan summary for the final list. Optional attributes that do not
 * apply to a given row (e.g. {@code cooldown_seconds} on a row with no {@code @CmdCD}) are
 * omitted from {@link #toFieldMap()} entirely rather than emitted as {@code null}, keeping
 * {@code surface.json} free of placeholder nulls.
 *
 * @since 6.3.0
 */
public final class SurfaceRow {

    private final String id;
    private final String kind;
    private final String origin;
    private final String cls;
    private final String className;
    private final String member;
    private final String format;
    private final List<String> aliases;
    private final String permission;
    private final String mappingPermission;
    private final boolean requireOp;
    private final boolean manualRegister;
    private final String cmdTarget;
    private final List<Map<String, Object>> params;
    private final List<String> senders;
    private final Integer cooldownSeconds;
    private final String usageLimit;
    private final Boolean usageLimitContainConsole;
    private final String trigger;

    private SurfaceRow(Builder builder) {
        this.id = builder.id;
        this.kind = builder.kind;
        this.origin = builder.origin;
        this.cls = builder.cls;
        this.className = builder.className;
        this.member = builder.member;
        this.format = builder.format;
        this.aliases = builder.aliases;
        this.permission = builder.permission;
        this.mappingPermission = builder.mappingPermission;
        this.requireOp = builder.requireOp;
        this.manualRegister = builder.manualRegister;
        this.cmdTarget = builder.cmdTarget;
        this.params = builder.params;
        this.senders = builder.senders;
        this.cooldownSeconds = builder.cooldownSeconds;
        this.usageLimit = builder.usageLimit;
        this.usageLimitContainConsole = builder.usageLimitContainConsole;
        this.trigger = builder.trigger;
    }

    public String getId() {
        return id;
    }

    /**
     * A sorted-key field map suitable for {@link CanonicalJsonWriter}. Only fields that apply to
     * this row are present; nulls are never emitted.
     *
     * @return the field map for this row
     */
    public Map<String, Object> toFieldMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind);
        map.put("origin", origin);
        map.put("cls", cls);
        map.put("class", className);
        map.put("member", member);
        map.put("format", format);
        map.put("aliases", aliases);
        map.put("permission", permission);
        // PermissionValidator checks the class-level (`permission`, above) and method-level
        // permission CONJUNCTIVELY -- a sender needs BOTH when both are declared, never either
        // one overriding the other (Codex review of PR #427). Emitted only when the mapping
        // itself declares one, so a row with no method-level permission stays unchanged from
        // before this field existed.
        if (mappingPermission != null && !mappingPermission.isEmpty()) {
            map.put("mapping_permission", mappingPermission);
        }
        map.put("require_op", requireOp);
        map.put("manual_register", manualRegister);
        if (cmdTarget != null) {
            map.put("cmd_target", cmdTarget);
        }
        map.put("params", params);
        map.put("senders", senders);
        if (cooldownSeconds != null) {
            map.put("cooldown_seconds", cooldownSeconds);
        }
        if (usageLimit != null) {
            map.put("usage_limit", usageLimit);
        }
        // UsageLockValidator.acquireLock checks ContainConsole() to decide whether a console
        // sender is subject to this limit at all -- `value()` alone (usage_limit above) does
        // not distinguish a mapping that locks out console from one that does not (Codex
        // review, restructure head). Emitted only alongside a non-null usage_limit AND only
        // when it differs from the annotation's own default (true, as of 6.3.0), so a row
        // whose ContainConsole is left at its default stays unchanged from before this field
        // existed.
        if (usageLimit != null && usageLimitContainConsole != null && !usageLimitContainConsole) {
            map.put("usage_limit_contain_console", usageLimitContainConsole);
        }
        map.put("trigger", trigger);
        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link SurfaceRow}. */
    public static final class Builder {
        private String id;
        private String kind;
        private String origin;
        private String cls;
        private String className;
        private String member;
        private String format;
        private List<String> aliases;
        private String permission;
        private String mappingPermission;
        private boolean requireOp;
        private boolean manualRegister;
        private String cmdTarget;
        private List<Map<String, Object>> params;
        private List<String> senders;
        private Integer cooldownSeconds;
        private String usageLimit;
        private Boolean usageLimitContainConsole;
        private String trigger;

        private Builder() {
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder kind(String value) {
            this.kind = value;
            return this;
        }

        public Builder origin(String value) {
            this.origin = value;
            return this;
        }

        public Builder cls(String value) {
            this.cls = value;
            return this;
        }

        public Builder className(String value) {
            this.className = value;
            return this;
        }

        public Builder member(String value) {
            this.member = value;
            return this;
        }

        public Builder format(String value) {
            this.format = value;
            return this;
        }

        public Builder aliases(List<String> value) {
            this.aliases = value;
            return this;
        }

        public Builder permission(String value) {
            this.permission = value;
            return this;
        }

        public Builder mappingPermission(String value) {
            this.mappingPermission = value;
            return this;
        }

        public Builder requireOp(boolean value) {
            this.requireOp = value;
            return this;
        }

        public Builder manualRegister(boolean value) {
            this.manualRegister = value;
            return this;
        }

        public Builder cmdTarget(String value) {
            this.cmdTarget = value;
            return this;
        }

        public Builder params(List<Map<String, Object>> value) {
            this.params = value;
            return this;
        }

        public Builder senders(List<String> value) {
            this.senders = value;
            return this;
        }

        public Builder cooldownSeconds(Integer value) {
            this.cooldownSeconds = value;
            return this;
        }

        public Builder usageLimit(String value) {
            this.usageLimit = value;
            return this;
        }

        public Builder usageLimitContainConsole(Boolean value) {
            this.usageLimitContainConsole = value;
            return this;
        }

        public Builder trigger(String value) {
            this.trigger = value;
            return this;
        }

        public SurfaceRow build() {
            return new SurfaceRow(this);
        }
    }
}
