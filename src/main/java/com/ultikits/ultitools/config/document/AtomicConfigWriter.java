package com.ultikits.ultitools.config.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;

import org.jetbrains.annotations.ApiStatus;

/**
 * The only way the config storage layer replaces a file.
 * <p>
 * The new text is written to a temporary file in the target's own directory, forced to disk, and moved
 * over the target with {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore sees either the old
 * file or the complete new one, never an empty or half-written file; and a failure at any step leaves
 * the target byte-identical and removes the temporary file. This replaces Bukkit's
 * {@code FileConfiguration#save(File)}, which truncates the target before writing it
 * (UltiKits/UltiTools-Reborn#574).
 *
 * @since 6.3.0
 */
@ApiStatus.Internal
public final class AtomicConfigWriter {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AtomicConfigWriter() {
    }

    /**
     * Replaces {@code target} with {@code text}, encoded as UTF-8.
     *
     * @param target the file to replace; its parent directory must exist
     * @param text   the complete new content
     * @throws IOException if any step fails; the target is then unchanged and no temporary file remains
     */
    public static void write(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = absolute.resolveSibling(temporaryName(absolute.getFileName().toString()));
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static String temporaryName(String fileName) {
        StringBuilder name = new StringBuilder(fileName).append(".tmp-");
        String hex = Long.toHexString(RANDOM.nextLong());
        for (int i = hex.length(); i < 16; i++) {
            name.append('0');
        }
        return name.append(hex).toString();
    }
}
