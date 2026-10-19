package com.ultikits.ultitools.interfaces.impl.data.json;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The file an entity id names in a JSON table directory, {@code <table directory>/<id>.json}, and
 * the rules an id must meet before an insert creates that file (question 12 A of the Phase 20.4
 * decisions). Every path the JSON backend builds from an id goes through
 * {@link #entityFile(Path, String)}, which refuses any result that is not exactly a file named
 * {@code <id>.json} directly in the table directory, whatever the file system's path parser made of
 * the name.
 * <p>
 * <b>Refused on every operating system</b> ({@link #creationRefusal(Path, String)}), because a
 * common file system cannot hold the name, or Windows treats it as something else:
 * <ul>
 *   <li>a Windows device name ({@code CON}, {@code PRN}, {@code AUX}, {@code NUL},
 *       {@code COM1}-{@code COM9}, {@code LPT1}-{@code LPT9}, and {@code COM} or {@code LPT}
 *       followed by a superscript one, two or three, in any letter case), with or without
 *       an extension: the part before the first dot is checked, since {@code con.json} is still the
 *       console device on Windows (the rule of sanitize-filename, filename-reserved-regex and
 *       Mercurial's store encoding);</li>
 *   <li>a name ending in a dot or a space, which Windows drops;</li>
 *   <li>{@code .} and {@code ..};</li>
 *   <li>a control character (U+0000-U+001F, U+007F), a slash or a backslash (Git's
 *       {@code check-ref-format} and pathvalidate's universal rules);</li>
 *   <li>a file name {@code <id>.json} longer than 255 bytes in UTF-8, the limit of ext4, NTFS
 *       and APFS.</li>
 * </ul>
 * <b>Refused only where the server's file system rejects it:</b> any other name that
 * {@link Path#resolve(String)} cannot turn into a path, or turns into a path other than
 * {@code <table directory>/<id>.json}. On Windows that is a name holding {@code < > : " | ? *},
 * including one that starts with a letter and a colon ({@code c:foo}, {@code p:<uuid>}), which
 * Windows reads as a drive prefix instead of rejecting; on Linux {@code :} stays accepted, so
 * UltiRemoteBag's {@code <uuid>:<page>} ids keep working there. A JSON data folder created on
 * Linux with such ids cannot be copied to Windows.
 * <p>
 * These rules apply only when an insert creates a new id. A file that already exists is never
 * renamed or rewritten because of them: it loads, and updates and deletes of its id keep working.
 *
 * @since 6.3.0
 */
final class JsonFileNames {

    /** The extension of every entity file. */
    static final String EXTENSION = ".json";

    /** The longest entity file name accepted at insert, in UTF-8 bytes. */
    static final int MAX_FILE_NAME_BYTES = 255;

    /**
     * Windows device names, matched against the upper-cased part of an id before its first dot.
     * Windows also reserves {@code COM} and {@code LPT} followed by a superscript one, two or three
     * (U+00B9, U+00B2, U+00B3).
     */
    private static final Pattern WINDOWS_DEVICE_NAME =
            Pattern.compile("(?:CON|PRN|AUX|NUL|COM[1-9\u00b9\u00b2\u00b3]|LPT[1-9\u00b9\u00b2\u00b3])");

    /** The default resolution: the file system of the table directory decides. */
    private static final Resolver DEFAULT_RESOLVER = Path::resolve;

    /**
     * Test seam: how a file name is resolved against a table directory. The default is
     * {@link Path#resolve(String)}; a test installs one that rejects names the way another
     * operating system's file system would, and resets it afterwards.
     */
    private static volatile Resolver resolver = DEFAULT_RESOLVER;

    private JsonFileNames() {
    }

    /**
     * The entity file of {@code key} in {@code tableDirectory}.
     * <p>
     * The result is checked, not trusted: its parent must be {@code tableDirectory} (both
     * normalized, on the same file system) and its file name must be exactly {@code <key>.json}.
     * A path parser that does not reject a name may still reinterpret it: Windows reads
     * {@code c:foo.json} as a drive-relative path, which resolves to {@code <table>/foo.json} (the
     * file of another id) or to a file on another drive, and any parser reads a separator or a
     * {@code ..} segment as a path step. Each of these is refused here, so insert, checkpoint and
     * recovery refuse it the same way they refuse a name the parser rejects.
     *
     * @param tableDirectory the table directory
     * @param key            the entity's key
     * @return {@code <tableDirectory>/<key>.json}
     * @throws InvalidPathException if the file system cannot name that file, or the name does not
     *                              resolve to a file named {@code <key>.json} directly in
     *                              {@code tableDirectory}
     */
    static Path entityFile(Path tableDirectory, String key) {
        String fileName = key + EXTENSION;
        Path file = resolver.resolve(tableDirectory, fileName);
        Path parent = file.getParent();
        Path name = file.getFileName();
        if (parent == null || name == null
                || parent.getFileSystem() != tableDirectory.getFileSystem()
                || !parent.normalize().equals(tableDirectory.normalize())
                || !fileName.equals(name.toString())) {
            throw new InvalidPathException(fileName, "it resolves to " + file + ", not to a file of that name directly in"
                    + " the table directory");
        }
        return file;
    }

    /**
     * Why an insert may not create the entity file of {@code key} in {@code tableDirectory}, or
     * {@code null} if it may (see the class description for the rules).
     *
     * @param tableDirectory the table directory the file would be created in
     * @param key            the new entity's key
     * @return the reason, phrased to follow "the id cannot name its file:", or {@code null}
     */
    static String creationRefusal(Path tableDirectory, String key) {
        if (key.isEmpty()) {
            return "it is empty";
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (isControl(c)) {
                return "it contains the control character " + unicodeEscape(c);
            }
            if (c == '/' || c == '\\') {
                return "it contains a path separator ('/' or '\\')";
            }
        }
        if (".".equals(key) || "..".equals(key)) {
            return "'.' and '..' name directories";
        }
        char last = key.charAt(key.length() - 1);
        if (last == '.' || last == ' ') {
            return "it ends in a dot or a space, which Windows drops from file names";
        }
        int dot = key.indexOf('.');
        String stem = dot < 0 ? key : key.substring(0, dot);
        if (WINDOWS_DEVICE_NAME.matcher(stem.toUpperCase(Locale.ROOT)).matches()) {
            return "'" + stem + "' is a Windows device name (CON, PRN, AUX, NUL, COM1-COM9, LPT1-LPT9, or COM or LPT"
                    + " followed by a superscript digit), which Windows reserves with or without an extension";
        }
        int bytes = (key + EXTENSION).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILE_NAME_BYTES) {
            return "its file name is " + bytes + " bytes long in UTF-8, more than the " + MAX_FILE_NAME_BYTES
                    + " bytes file systems allow";
        }
        try {
            entityFile(tableDirectory, key);
        } catch (InvalidPathException invalid) {
            return "this server's file system cannot name its file (" + invalid.getMessage() + ")";
        }
        return null;
    }

    /**
     * {@code key} as messages show it: every control character as a {@code \}{@code uXXXX} escape,
     * so a log line never carries a raw control character.
     *
     * @param key an entity key
     * @return the key with its control characters escaped
     */
    static String shown(String key) {
        StringBuilder shown = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (isControl(c)) {
                shown.append(unicodeEscape(c));
            } else {
                shown.append(c);
            }
        }
        return shown.toString();
    }

    private static boolean isControl(char c) {
        return c < 0x20 || c == 0x7f;
    }

    private static String unicodeEscape(char c) {
        return String.format(Locale.ROOT, "\\u%04x", (int) c);
    }

    static void installResolver(Resolver replacement) {
        resolver = replacement == null ? DEFAULT_RESOLVER : replacement;
    }

    static void resetResolver() {
        resolver = DEFAULT_RESOLVER;
    }

    /** Resolves a file name against a directory; replaceable in tests. */
    interface Resolver {
        Path resolve(Path directory, String fileName);
    }
}
