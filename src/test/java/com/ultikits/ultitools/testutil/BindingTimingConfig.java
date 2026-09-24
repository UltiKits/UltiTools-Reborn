package com.ultikits.ultitools.testutil;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;

import lombok.Getter;
import lombok.Setter;

/**
 * Config entity fixture for the #531 config-binding tests: {@code @Scheduled(config = ...,
 * periodKey = ...)} and {@code @CmdCD(config = ..., key = ...)} name this class and one of its
 * {@code @ConfigEntry} paths. Tests set the fields directly -- nothing here ever reads or writes a
 * file, because the framework reads the bound value from the entity's field, not from YAML.
 */
@Getter
@Setter
public class BindingTimingConfig extends AbstractConfigEntity {

    /** Repeat interval, seconds. */
    @ConfigEntry(path = "timer.period")
    private int periodSeconds = 5;

    /** Initial delay, seconds -- a {@code long} field, to cover the other primitive integral type. */
    @ConfigEntry(path = "timer.delay")
    private long delaySeconds = 10L;

    /** A boxed integral field, so a {@code null} value can be tested. */
    @ConfigEntry(path = "timer.boxed")
    private Integer boxedSeconds = 3;

    /** A boxed {@code Long} field, so an overflowing seconds value can be tested. */
    @ConfigEntry(path = "timer.huge")
    private Long hugeSeconds = 1L;

    /** Not integral: binding to it must be refused. */
    @ConfigEntry(path = "timer.ratio")
    private double ratio = 1.5D;

    /** Not numeric: binding to it must be refused. */
    @ConfigEntry(path = "timer.name")
    private String name = "timer";

    /** A cooldown, seconds. */
    @ConfigEntry(path = "cooldown.wild")
    private int wildCooldown = 60;

    /** No {@code @ConfigEntry}: not a key, so binding to its name must be refused. */
    private int notAnEntry = 7;

    public BindingTimingConfig() {
        super("config/timing.yml");
    }
}
