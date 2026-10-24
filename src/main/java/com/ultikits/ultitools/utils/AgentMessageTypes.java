package com.ultikits.ultitools.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class AgentMessageTypes {
    static final String CONTRACT_VERSION = "2.0.0";
    enum Direction { UP, DOWN, BOTH }
    enum ReplyTo { REQUIRED, FORBIDDEN, OPTIONAL }

    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> specs = new LinkedHashMap<>();
        add(specs, "hello", Direction.UP, ReplyTo.FORBIDDEN, null, false, false);
        add(specs, "settings.changed", Direction.UP, ReplyTo.FORBIDDEN, null, false, false);
        add(specs, "welcome", Direction.DOWN, ReplyTo.REQUIRED, null, false, false);
        add(specs, "ack", Direction.DOWN, ReplyTo.FORBIDDEN, null, false, false);
        add(specs, "error", Direction.BOTH, ReplyTo.OPTIONAL, null, false, false);
        add(specs, "outbox.overflow", Direction.UP, ReplyTo.FORBIDDEN, null, false, false);
        add(specs, "credential.rotate", Direction.DOWN, ReplyTo.FORBIDDEN, null, false, false);
        add(specs, "credential.rotate.ack", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "command.execute", Direction.DOWN, ReplyTo.FORBIDDEN, "commands", true, false);
        add(specs, "command.ack", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "command.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "server.power", Direction.DOWN, ReplyTo.FORBIDDEN, "power", true, false);
        add(specs, "server.power.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.join", Direction.UP, ReplyTo.FORBIDDEN, "events:players", false, true);
        add(specs, "player.leave", Direction.UP, ReplyTo.FORBIDDEN, "events:players", false, true);
        add(specs, "player.command", Direction.UP, ReplyTo.FORBIDDEN, "events:commands", false, false);
        add(specs, "error.report", Direction.UP, ReplyTo.FORBIDDEN, "errors", false, false);
        add(specs, "status", Direction.UP, ReplyTo.FORBIDDEN, "telemetry", false, false);
        add(specs, "players.snapshot", Direction.UP, ReplyTo.FORBIDDEN, "telemetry", false, false);
        add(specs, "plugins.snapshot", Direction.UP, ReplyTo.FORBIDDEN, "telemetry", false, false);
        add(specs, "worlds.snapshot", Direction.UP, ReplyTo.FORBIDDEN, "telemetry", false, false);
        add(specs, "snapshot.request", Direction.DOWN, ReplyTo.FORBIDDEN, "telemetry", false, false);
        add(specs, "snapshot.request.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "log.lines", Direction.UP, ReplyTo.FORBIDDEN, "logs", false, false);
        add(specs, "op.progress", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "files.list", Direction.DOWN, ReplyTo.FORBIDDEN, "files:read", false, false);
        add(specs, "files.list.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "files.read", Direction.DOWN, ReplyTo.FORBIDDEN, "files:read", false, false);
        add(specs, "files.read.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "files.write", Direction.DOWN, ReplyTo.FORBIDDEN, "files:write", false, false);
        add(specs, "files.write.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "files.delete", Direction.DOWN, ReplyTo.FORBIDDEN, "files:delete", false, false);
        add(specs, "files.delete.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "config.tree", Direction.DOWN, ReplyTo.FORBIDDEN, "config:read", false, false);
        add(specs, "config.tree.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "config.entries", Direction.DOWN, ReplyTo.FORBIDDEN, "config:read", false, false);
        add(specs, "config.entries.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "config.update", Direction.DOWN, ReplyTo.FORBIDDEN, "config:write", false, false);
        add(specs, "config.update.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "properties.get", Direction.DOWN, ReplyTo.FORBIDDEN, "properties:read", false, false);
        add(specs, "properties.get.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "properties.update", Direction.DOWN, ReplyTo.FORBIDDEN, "properties:write", false, false);
        add(specs, "properties.update.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "backups.list", Direction.DOWN, ReplyTo.FORBIDDEN, "backups", false, false);
        add(specs, "backups.list.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "backups.create", Direction.DOWN, ReplyTo.FORBIDDEN, "backups", false, false);
        add(specs, "backups.create.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "backups.restore", Direction.DOWN, ReplyTo.FORBIDDEN, "backups", true, false);
        add(specs, "backups.restore.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "backups.delete", Direction.DOWN, ReplyTo.FORBIDDEN, "backups", false, false);
        add(specs, "backups.delete.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.action", Direction.DOWN, ReplyTo.FORBIDDEN, "players:actions", false, false);
        add(specs, "player.action.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        // This literal is a routing sentinel, not a hello/welcome capability name.
        // The integration predicate must accept it; the module handler checks the
        // payload's module and the negotiated modules:<id>@<version> capability.
        add(specs, "module.request", Direction.DOWN, ReplyTo.FORBIDDEN, "fromPayload:module", false, false);
        add(specs, "module.response", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.link.create", Direction.UP, ReplyTo.FORBIDDEN, "link", false, false);
        add(specs, "player.link.created", Direction.DOWN, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.link.completed", Direction.DOWN, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.link.completed.result", Direction.UP, ReplyTo.REQUIRED, null, false, false);
        add(specs, "player.link.expired", Direction.DOWN, ReplyTo.REQUIRED, null, false, false);
        SPECS = Collections.unmodifiableMap(specs);
    }

    private AgentMessageTypes() { }

    static Spec spec(String type) { return SPECS.get(type); }
    static Set<String> types() { return SPECS.keySet(); }

    private static void add(Map<String, Spec> specs, String type, Direction direction, ReplyTo replyTo,
                            String capability, boolean ackFirst, boolean reliable) {
        specs.put(type, new Spec(direction, replyTo, capability, ackFirst, reliable));
    }

    static final class Spec {
        private final Direction direction;
        private final ReplyTo replyTo;
        private final String capability;
        private final boolean ackFirst;
        private final boolean reliable;

        private Spec(Direction direction, ReplyTo replyTo, String capability, boolean ackFirst, boolean reliable) {
            this.direction = direction;
            this.replyTo = replyTo;
            this.capability = capability;
            this.ackFirst = ackFirst;
            this.reliable = reliable;
        }

        Direction direction() { return direction; }
        ReplyTo replyTo() { return replyTo; }
        String capability() { return capability; }
        boolean ackFirst() { return ackFirst; }
        boolean reliable() { return reliable; }
    }
}
