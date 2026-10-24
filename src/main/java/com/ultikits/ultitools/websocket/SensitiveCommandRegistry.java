package com.ultikits.ultitools.websocket;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The table of commands whose arguments must never reach UltiCloud, and the redaction rule that
 * enforces it.
 * <p>
 * A command such as {@code /login <password>} carries a secret in its arguments. Anything the
 * framework sends to UltiCloud (the panel's console log stream, including the start-up lines
 * replayed once the stream opens; error reports; chat events) is passed through
 * {@link #redactText(String)} on its way out, which keeps a registered command's name and replaces
 * everything after it with {@code ***}. A player-command event never carries arguments at all,
 * registered or not: it carries {@link #commandName(String)} only.
 * <p>
 * <b>Only data sent to UltiCloud is redacted.</b> The server's own console, its log files and the
 * {@link java.util.logging.LogRecord} objects other handlers see keep the original text: the
 * operator who can read the local console can already read what the players typed.
 * <p>
 * <b>Matching.</b> A command line matches when its first word, with one leading {@code /} and a
 * {@code namespace:} prefix removed, equals a registered name, compared case-insensitively
 * ({@link Locale#ROOT}, so a Turkish or other default locale does not change the result). With
 * {@code login} registered, all of {@code /login}, {@code /LOGIN}, {@code ultilogin:login} and
 * {@code /ultilogin:Login} match. Names are stored in that normalised form, so registering
 * {@code ultilogin:login} registers {@code login}.
 * <p>
 * <b>The table starts empty.</b> The framework registers nothing on its own; a module that owns a
 * secret-carrying command registers it, and every alias, from its enable hook:
 * <pre>{@code
 * public boolean registerSelf() {
 *     UltiTools.getInstance().getSensitiveCommandRegistry()
 *             .register("login", "l", "register", "reg", "changepassword");
 *     return true;
 * }
 * }</pre>
 * Registration is safe from any thread, takes effect immediately whether or not the server is
 * linked to UltiCloud, and is idempotent, so registering again after a module reload is harmless.
 * There is deliberately no way to unregister: a name stays redacted until the server restarts,
 * which errs on the side of redacting too much rather than too little, and means one module cannot
 * weaken another module's redaction.
 * <p>
 * Unlike the rest of this package, this class is a stable, public extension point for modules.
 *
 * @since 6.3.1
 */
public final class SensitiveCommandRegistry {

    /** What replaces a registered command's arguments. */
    private static final String REDACTED = " ***";

    /** The registered names, normalised by {@link #normalize(String)}. */
    private final Set<String> names = ConcurrentHashMap.newKeySet();

    /**
     * Creates an empty registry.
     */
    public SensitiveCommandRegistry() {
        // Starts empty by design: the framework registers no command of its own.
    }

    /**
     * Registers command names whose arguments must not reach UltiCloud.
     * <p>
     * Each name is normalised first ({@link #normalize(String)}), so {@code "/Login"},
     * {@code "login"} and {@code "ultilogin:login"} all register {@code login}. A {@code null}
     * array, and {@code null} or blank names, are ignored. Registering a name twice is harmless.
     *
     * @param commandNames the command names and aliases to register
     */
    public void register(String... commandNames) {
        if (commandNames == null) {
            return;
        }
        for (String name : commandNames) {
            String normalized = normalize(name);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
    }

    /**
     * Whether a command line starts with a registered command.
     *
     * @param commandLine a command line as typed, with or without the leading {@code /}
     * @return {@code true} if the line's {@link #commandName(String) command name} is registered
     */
    public boolean isSensitive(String commandLine) {
        String name = commandName(commandLine);
        return !name.isEmpty() && names.contains(name);
    }

    /**
     * Normalises one command token: strips one leading {@code /} and a {@code namespace:} prefix,
     * and lower-cases the rest with {@link Locale#ROOT}.
     * <p>
     * {@code "/login"} and {@code "/UltiLogin:LOGIN"} give {@code "login"}; {@code "//login"} gives
     * {@code "/login"} (only one slash is stripped, so a {@code //} command stays distinct);
     * {@code "login:"} gives an empty string.
     *
     * @param commandToken one command token, possibly {@code null}
     * @return the normalised name, or an empty string for a {@code null} or blank token
     */
    public static String normalize(String commandToken) {
        if (commandToken == null) {
            return "";
        }
        String token = commandToken.trim();
        if (token.startsWith("/")) {
            token = token.substring(1);
        }
        int colon = token.lastIndexOf(':');
        if (colon >= 0) {
            token = token.substring(colon + 1);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    /**
     * The normalised name of a command line's command: its first whitespace-delimited token,
     * passed through {@link #normalize(String)}.
     * <p>
     * {@code "/ultilogin:Login pw"} gives {@code "login"}, {@code "  /spawn"} gives
     * {@code "spawn"}, {@code "/MSG Alex hi"} gives {@code "msg"}. This is the only part of a
     * player's command that the framework sends to UltiCloud in a command event.
     *
     * @param commandLine a command line as typed, possibly {@code null}
     * @return the normalised command name, or an empty string for a {@code null} or blank line
     */
    public static String commandName(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        int start = skipWhitespace(commandLine, 0, commandLine.length());
        int end = start;
        while (end < commandLine.length() && !Character.isWhitespace(commandLine.charAt(end))) {
            end++;
        }
        return normalize(commandLine.substring(start, end));
    }

    /**
     * Redacts one command line: when its command is registered and arguments follow, keeps the
     * command as typed and replaces the arguments with {@code ***}.
     * <p>
     * With {@code login} registered, {@code "/login hunter2"} gives {@code "/login ***"}, while
     * {@code "/login"} and {@code "/login   "} (no arguments) are returned unchanged, as is any
     * line whose command is not registered.
     *
     * @param commandLine a command line as typed, possibly {@code null}
     * @return the redacted line, or the line itself when nothing needs redacting
     */
    public String redactCommandLine(String commandLine) {
        if (commandLine == null || names.isEmpty()) {
            return commandLine;
        }
        int start = skipWhitespace(commandLine, 0, commandLine.length());
        int end = start;
        while (end < commandLine.length() && !Character.isWhitespace(commandLine.charAt(end))) {
            end++;
        }
        if (names.contains(normalize(commandLine.substring(start, end)))
                && hasArgument(commandLine, end, commandLine.length())) {
            return commandLine.substring(0, end) + REDACTED;
        }
        return commandLine;
    }

    /**
     * Redacts free text, such as a log line, an exception message or a stack trace, before it is
     * sent to UltiCloud.
     * <p>
     * Each line is handled on its own. Wherever {@code /} followed by a registered command appears
     * at the start of a line or after whitespace, the command is kept and everything after it on
     * that line is replaced with {@code ***}, provided something non-blank follows. For example
     * {@code "Steve issued server command: /login hunter2"} becomes
     * {@code "Steve issued server command: /login ***"}. Text with no registered command is
     * returned unchanged.
     *
     * @param text the text, possibly {@code null}
     * @return the redacted text, or the text itself when nothing needs redacting
     */
    public String redactText(String text) {
        if (text == null || text.isEmpty() || names.isEmpty()) {
            return text;
        }
        StringBuilder out = null;
        int copied = 0;
        int length = text.length();
        int lineStart = 0;
        while (lineStart < length) {
            int lineEnd = lineStart;
            while (lineEnd < length && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            int cut = findRedactionPoint(text, lineStart, lineEnd);
            if (cut >= 0) {
                if (out == null) {
                    out = new StringBuilder(length);
                }
                out.append(text, copied, cut).append(REDACTED);
                copied = lineEnd;
            }
            lineStart = lineEnd + 1;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, length).toString();
    }

    /**
     * A snapshot of the registered names, in their normalised form.
     *
     * @return an unmodifiable copy; later registrations do not change it
     */
    public Set<String> registeredNames() {
        return Collections.unmodifiableSet(new TreeSet<>(names));
    }

    /**
     * Finds where one line must be cut: the end of the first registered command that is followed
     * by an argument, or {@code -1} when the line needs no redaction.
     */
    private int findRedactionPoint(String text, int lineStart, int lineEnd) {
        int i = lineStart;
        while (i < lineEnd) {
            if (text.charAt(i) == '/' && (i == lineStart || Character.isWhitespace(text.charAt(i - 1)))) {
                int end = i;
                while (end < lineEnd && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                if (names.contains(normalize(text.substring(i, end))) && hasArgument(text, end, lineEnd)) {
                    return end;
                }
                i = end;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Whether anything non-blank lies in {@code [from, to)}. */
    private static boolean hasArgument(String text, int from, int to) {
        return skipWhitespace(text, from, to) < to;
    }

    private static int skipWhitespace(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
