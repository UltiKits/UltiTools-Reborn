package com.ultikits.ultitools.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件操作工具类
 * <p>
 * 替代 hutool FileUtil / FileNameUtil
 * 
 * @author wisdomme
 * @since 6.2.0
 */
public final class FileUtils {
    
    private FileUtils() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 创建文件，如果父目录不存在会自动创建
     *
     * @param file 文件
     * @return 是否创建成功
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
     * 删除文件或目录
     *
     * @param file 文件或目录
     * @return 是否删除成功
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
     * 递归删除目录
     *
     * @param directory 目录
     * @return 是否删除成功
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
     * 获取文件主名（不含扩展名）
     *
     * @param file 文件
     * @return 主文件名
     */
    public static String mainName(File file) {
        if (file == null) {
            return null;
        }
        return mainName(file.getName());
    }
    
    /**
     * 获取文件主名（不含扩展名）
     *
     * @param fileName 文件名
     * @return 主文件名
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
     * 获取文件扩展名
     *
     * @param file 文件
     * @return 扩展名（不含点）
     */
    public static String extName(File file) {
        if (file == null) {
            return null;
        }
        return extName(file.getName());
    }
    
    /**
     * 获取文件扩展名
     *
     * @param fileName 文件名
     * @return 扩展名（不含点）
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
     * 确保目录存在，不存在则创建
     *
     * @param dir 目录
     * @return 目录
     */
    public static File mkdir(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
    
    /**
     * 确保文件的父目录存在
     *
     * @param file 文件
     * @return 文件
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
