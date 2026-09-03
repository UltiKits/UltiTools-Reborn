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
 *
 * @author wisdomme
 * @since 6.0.0
 */
public class HttpDownloadUtils {

    /**
     * Progress callback interface for download operations.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * Called when download progress is updated.
         *
         * @param bytesDownloaded Bytes downloaded so far
         * @param totalBytes      Total bytes to download, -1 if unknown
         */
        void onProgress(long bytesDownloaded, long totalBytes);
    }
    /**
     * Download file from URL.
     *
     * @param urlString Download URL
     * @param fileName  File name
     * @param savePath  Save path
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
            // Set the timeouts
            conn.setConnectTimeout(10 * 1000); // 10-second connect timeout
            conn.setReadTimeout(30 * 1000);    // 30-second read timeout
            
            // Set a modern user agent
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // Check the HTTP response code
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // Create the save directory
            File saveDir = new File(savePath);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + savePath);
            }

            // Path traversal protection: strip directory components from fileName
            String sanitizedName = new File(fileName).getName();
            if (sanitizedName.isEmpty() || sanitizedName.equals("..") || sanitizedName.equals(".")) {
                throw new IllegalArgumentException("Invalid file name: " + fileName);
            }
            File file = new File(saveDir, sanitizedName);
            if (!file.getCanonicalPath().startsWith(saveDir.getCanonicalPath() + File.separator)) {
                throw new SecurityException("Path traversal detected: " + fileName);
            }

            // Use try-with-resources to ensure resources are closed properly
            try (InputStream inputStream = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(file)) {

                byte[] buffer = new byte[8192]; // Larger buffer for better throughput
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
     *
     * @param urlString        Download URL
     * @param fileName         File name
     * @param savePath         Save path
     * @param progressCallback Progress callback
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
            // Set the timeouts
            conn.setConnectTimeout(10 * 1000); // 10-second connect timeout
            conn.setReadTimeout(30 * 1000);    // 30-second read timeout
            
            // Set a modern user agent
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // Check the HTTP response code
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // Get the file size
            long contentLength = conn.getContentLengthLong();

            // Create the save directory
            File saveDir = new File(savePath);
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + savePath);
            }

            // Path traversal protection: strip directory components from fileName
            String sanitizedName = new File(fileName).getName();
            if (sanitizedName.isEmpty() || sanitizedName.equals("..") || sanitizedName.equals(".")) {
                throw new IllegalArgumentException("Invalid file name: " + fileName);
            }
            File file = new File(saveDir, sanitizedName);
            if (!file.getCanonicalPath().startsWith(saveDir.getCanonicalPath() + File.separator)) {
                throw new SecurityException("Path traversal detected: " + fileName);
            }

            // Use try-with-resources to ensure resources are closed properly
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
     *
     * @param urlString Download URL
     * @return Downloaded content as byte array
     * @throws IOException if an I/O error occurs during download
     */
    public static byte[] downloadToByteArray(String urlString) throws IOException {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("URL string cannot be null or empty");
        }

        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            // Set the timeouts
            conn.setConnectTimeout(10 * 1000); // 10-second connect timeout
            conn.setReadTimeout(30 * 1000);    // 30-second read timeout
            
            // Set a modern user agent
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // Check the HTTP response code
            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode + " - " + conn.getResponseMessage());
            }

            // Use try-with-resources to read the content
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
