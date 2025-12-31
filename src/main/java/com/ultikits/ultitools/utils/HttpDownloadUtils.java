package com.ultikits.ultitools.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Utility class for HTTP file download operations.
 * Provides methods to download files from URLs with progress tracking support.
 * <br>
 * HTTP文件下载操作的实用工具类。
 * 提供从URL下载文件的方法，支持进度跟踪。
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class HttpDownloadUtils {
    
    /**
     * Progress callback interface for download operations.
     * <br>
     * 下载操作的进度回调接口。
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * Called when download progress is updated.
         * <br>
         * 当下载进度更新时调用。
         *
         * @param bytesDownloaded Bytes downloaded so far <br> 已下载的字节数
         * @param totalBytes      Total bytes to download, -1 if unknown <br> 总字节数，未知时为-1
         */
        void onProgress(long bytesDownloaded, long totalBytes);
    }
    /**
     * Download file from URL.
     * <br>
     * 从URL下载文件。
     *
     * @param urlString Download URL <br> 下载地址
     * @param fileName  File name <br> 文件名
     * @param savePath  Save path <br> 保存路径
     * @throws IOException if an I/O error occurs during download
     */
    public static void download(String urlString, String fileName, String savePath) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("URL string cannot be null or empty");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }
        if (savePath == null || savePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Save path cannot be null or empty");
        }

        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            // 设置超时时间
            conn.setConnectTimeout(10 * 1000); // 10秒连接超时
            conn.setReadTimeout(30 * 1000);    // 30秒读取超时
            
            // 设置现代化的用户代理
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // 检查HTTP响应码
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // 创建保存目录
            File saveDir = new File(savePath);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + savePath);
            }

            File file = new File(saveDir, fileName);
            
            // 使用try-with-resources确保资源正确关闭
            try (InputStream inputStream = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(file)) {
                
                byte[] buffer = new byte[8192]; // 增大缓冲区提高性能
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download file from URL with progress callback.
     * <br>
     * 从URL下载文件并提供进度回调。
     *
     * @param urlString        Download URL <br> 下载地址
     * @param fileName         File name <br> 文件名
     * @param savePath         Save path <br> 保存路径
     * @param progressCallback Progress callback <br> 进度回调
     * @throws IOException if an I/O error occurs during download
     */
    public static void download(String urlString, String fileName, String savePath, 
                               ProgressCallback progressCallback) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("URL string cannot be null or empty");
        }
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }
        if (savePath == null || savePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Save path cannot be null or empty");
        }

        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            // 设置超时时间
            conn.setConnectTimeout(10 * 1000); // 10秒连接超时
            conn.setReadTimeout(30 * 1000);    // 30秒读取超时
            
            // 设置现代化的用户代理
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // 检查HTTP响应码
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // 获取文件大小
            long contentLength = conn.getContentLengthLong();

            // 创建保存目录
            File saveDir = new File(savePath);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + savePath);
            }

            File file = new File(saveDir, fileName);
            
            // 使用try-with-resources确保资源正确关闭
            try (InputStream inputStream = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(file)) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress(totalBytesRead, contentLength);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Download content from URL to byte array.
     * <br>
     * 从URL下载内容到字节数组。
     *
     * @param urlString Download URL <br> 下载地址
     * @return Downloaded content as byte array <br> 下载的内容作为字节数组
     * @throws IOException if an I/O error occurs during download
     */
    public static byte[] downloadToByteArray(String urlString) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("URL string cannot be null or empty");
        }

        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            // 设置超时时间
            conn.setConnectTimeout(10 * 1000); // 10秒连接超时
            conn.setReadTimeout(30 * 1000);    // 30秒读取超时
            
            // 设置现代化的用户代理
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // 检查HTTP响应码
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // 使用try-with-resources读取内容
            try (InputStream inputStream = conn.getInputStream();
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
