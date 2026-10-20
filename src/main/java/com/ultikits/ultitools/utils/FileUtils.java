package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * File operation utility class.
 * <p>
 * Replaces hutool's FileUtil / FileNameUtil.
 *
 * @author wisdomme
 * @since 6.2.0
 */
public final class FileUtils {

    private FileUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates a file, automatically creating the parent directory if it does not exist.
     *
     * @param file the file
     * @return whether creation succeeded
     */
    public static boolean touch(File file) {
        if (file == null) {
            return false;
        }
        if (file.exists()) {
            return true;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try {
            return file.createNewFile();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Deletes a file or directory.
     *
     * @param file the file or directory
     * @return whether deletion succeeded
     */
    public static boolean del(File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            return deleteDirectory(file);
        }

        return file.delete();
    }

    /**
     * Recursively deletes a directory.
     *
     * @param directory the directory
     * @return whether deletion succeeded
     */
    public static boolean deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return true;
        }

        try (Stream<Path> walk = Files.walk(directory.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Gets the file's base name (without extension).
     *
     * @param file the file
     * @return the base file name
     */
    public static String mainName(File file) {
        if (file == null) {
            return null;
        }
        return mainName(file.getName());
    }

    /**
     * Gets the file's base name (without extension).
     *
     * @param fileName the file name
     * @return the base file name
     */
    public static String mainName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return fileName;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    /**
     * Gets the file extension.
     *
     * @param file the file
     * @return the extension (without the dot)
     */
    public static String extName(File file) {
        if (file == null) {
            return null;
        }
        return extName(file.getName());
    }

    /**
     * Gets the file extension.
     *
     * @param fileName the file name
     * @return the extension (without the dot)
     */
    public static String extName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex == -1 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1);
    }

    /**
     * Ensures the directory exists, creating it if not.
     *
     * @param dir the directory
     * @return the directory
     */
    public static File mkdir(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Ensures the file's parent directory exists.
     *
     * @param file the file
     * @return the file
     */
    public static File mkParentDirs(File file) {
        if (file != null) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }
        return file;
    }
}
