package com.ultikits.ultitools.manager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

/**
 * 文件操作管理器
 * 负责处理来自WebSocket的文件操作请求
 */
public class FileOperationManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final File serverRoot;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private static final Set<String> BLOCKED_FILES = new HashSet<String>(Arrays.asList(
        "server.properties", "ops.json", "whitelist.json",
        "banned-ips.json", "banned-players.json", "eula.txt",
        "usercache.json", "bukkit.yml", "spigot.yml",
        "paper.yml", "paper-global.yml", "paper-world-defaults.yml"
    ));

    private static final Set<String> BLOCKED_EXTENSIONS = new HashSet<String>(Arrays.asList(
        ".jar", ".sh", ".bat", ".exe", ".class"
    ));

    public FileOperationManager() {
        this.serverRoot = new File(System.getProperty("user.dir"));
    }
    
    /**
     * 设置WebSocket客户端
     * @param client WebSocket客户端
     */
    public void setWebSocketClient(UltiPanelWebSocketClient client) {
        this.webSocketClient = client;
    }

    /**
     * 检查文件路径是否允许远程访问
     * Check if a file path is allowed for remote access.
     *
     * @param path 文件路径
     * @return 是否允许访问
     */
    public boolean isPathAllowed(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        String normalized = path.trim().replace("\\", "/");
        // Strip leading slash
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        // Check blocked file names (basename only)
        String fileName = normalized.contains("/")
            ? normalized.substring(normalized.lastIndexOf("/") + 1)
            : normalized;

        if (BLOCKED_FILES.contains(fileName.toLowerCase())) {
            return false;
        }

        // Check blocked extensions
        for (String ext : BLOCKED_EXTENSIONS) {
            if (fileName.toLowerCase().endsWith(ext)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 处理文件操作请求
     */
    public void handleFileOperation(JsonObject operationData) {
        try {
            String operation = operationData.has("operation") && !operationData.get("operation").isJsonNull() 
                ? operationData.get("operation").getAsString() : null;
            String path = operationData.has("path") && !operationData.get("path").isJsonNull() 
                ? operationData.get("path").getAsString() : null;
            String operationId = operationData.has("operationId") && !operationData.get("operationId").isJsonNull() 
                ? operationData.get("operationId").getAsString() : null;
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("处理文件操作: %s, 路径: %s (ID: %s)", operation, path, operationId));
            
            // 异步处理文件操作
            CompletableFuture.runAsync(() -> {
                switch (operation) {
                    case "read":
                        handleReadOperation(path, operationData, operationId);
                        break;
                    case "write":
                        handleWriteOperation(path, operationData, operationId);
                        break;
                    case "list":
                        handleListOperation(path, operationData, operationId);
                        break;
                    case "delete":
                        handleDeleteOperation(path, operationData, operationId);
                        break;
                    default:
                        sendFileOperationResult(operationId, operation, path, false, 
                            "Unsupported operation: " + operation, null);
                }
            });
            
        } catch (Exception e) {
            String operationId = operationData.has("operationId") && !operationData.get("operationId").isJsonNull() 
                ? operationData.get("operationId").getAsString() : null;
            UltiTools.getInstance().getLogger().log(Level.WARNING, "文件操作处理失败: " + e.getMessage());
            sendFileOperationResult(operationId, "unknown", "unknown", false, 
                "Error processing file operation: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件读取操作
     */
    private void handleReadOperation(String path, JsonObject operationData, String operationId) {
        try {
            if (!isPathAllowed(path)) {
                sendFileOperationResult(operationId, "read", path, false,
                    "Access denied: this file is protected", null);
                return;
            }

            File file = getSecureFile(path);
            if (!file.exists()) {
                sendFileOperationResult(operationId, "read", path, false, "File not found", null);
                return;
            }
            
            if (!file.isFile()) {
                sendFileOperationResult(operationId, "read", path, false, "Path is not a file", null);
                return;
            }
            
            int limit = operationData.has("limit") && !operationData.get("limit").isJsonNull() 
                ? operationData.get("limit").getAsInt() : 0;
            if (limit <= 0) limit = 1000; // 默认限制1000行
            
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < limit) {
                    content.append(line).append("\n");
                    lineCount++;
                }
            }
            
            JsonObject resultData = new JsonObject();
            resultData.addProperty("content", content.toString().trim());
            resultData.addProperty("size", file.length());
            resultData.addProperty("lastModified", file.lastModified());
            resultData.addProperty("linesRead", content.toString().split("\n").length);
            
            sendFileOperationResult(operationId, "read", path, true, "File read successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "read", path, false, 
                "Error reading file: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件写入操作
     */
    private void handleWriteOperation(String path, JsonObject operationData, String operationId) {
        try {
            if (!isPathAllowed(path)) {
                sendFileOperationResult(operationId, "write", path, false,
                    "Access denied: this file is protected", null);
                return;
            }

            File file = getSecureFile(path);
            String content = operationData.has("content") && !operationData.get("content").isJsonNull()
                ? operationData.get("content").getAsString() : null;
            boolean append = operationData.has("append") && !operationData.get("append").isJsonNull()
                && operationData.get("append").getAsBoolean();

            if (content == null) {
                sendFileOperationResult(operationId, "write", path, false, "Content cannot be null", null);
                return;
            }
            
            // 确保父目录存在
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            try (FileWriter writer = new FileWriter(file, append)) {
                writer.write(content);
            }
            
            JsonObject resultData = new JsonObject();
            resultData.addProperty("bytesWritten", content.getBytes().length);
            resultData.addProperty("fileSize", file.length());
            
            sendFileOperationResult(operationId, "write", path, true, "File written successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "write", path, false, 
                "Error writing file: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理目录列表操作
     */
    private void handleListOperation(String path, JsonObject operationData, String operationId) {
        try {
            File dir = getSecureFile(path);
            if (!dir.exists()) {
                sendFileOperationResult(operationId, "list", path, false, "Directory not found", null);
                return;
            }
            
            if (!dir.isDirectory()) {
                sendFileOperationResult(operationId, "list", path, false, "Path is not a directory", null);
                return;
            }
            
            File[] files = dir.listFiles();
            if (files == null) {
                sendFileOperationResult(operationId, "list", path, false, "Cannot read directory", null);
                return;
            }
            
            JsonArray fileList = new JsonArray();
            for (File file : files) {
                // Filter out protected files from directory listings
                String childPath = (path == null || path.isEmpty() || path.equals("/"))
                    ? file.getName()
                    : path + "/" + file.getName();
                if (!file.isDirectory() && !isPathAllowed(childPath)) {
                    continue;
                }

                JsonObject fileInfo = new JsonObject();
                fileInfo.addProperty("name", file.getName());
                fileInfo.addProperty("isDirectory", file.isDirectory());
                fileInfo.addProperty("size", file.isDirectory() ? 0 : file.length());
                fileInfo.addProperty("lastModified", file.lastModified());
                fileInfo.addProperty("readable", file.canRead());
                fileInfo.addProperty("writable", file.canWrite());
                fileList.add(fileInfo);
            }
            
            JsonObject resultData = new JsonObject();
            resultData.add("files", fileList);
            resultData.addProperty("totalCount", fileList.size());
            
            sendFileOperationResult(operationId, "list", path, true, "Directory listed successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "list", path, false, 
                "Error listing directory: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件删除操作
     */
    private void handleDeleteOperation(String path, JsonObject operationData, String operationId) {
        try {
            if (!isPathAllowed(path)) {
                sendFileOperationResult(operationId, "delete", path, false,
                    "Access denied: this file is protected", null);
                return;
            }

            File file = getSecureFile(path);
            if (!file.exists()) {
                sendFileOperationResult(operationId, "delete", path, false, "File not found", null);
                return;
            }
            
            boolean deleted = false;
            if (file.isDirectory()) {
                // 递归删除目录（谨慎操作）
                deleted = deleteDirectory(file);
            } else {
                deleted = file.delete();
            }
            
            if (deleted) {
                sendFileOperationResult(operationId, "delete", path, true, "File deleted successfully", null);
            } else {
                sendFileOperationResult(operationId, "delete", path, false, "Failed to delete file", null);
            }
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "delete", path, false, 
                "Error deleting file: " + e.getMessage(), null);
        }
    }
    
    /**
     * 递归删除目录
     */
    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }
    
    /**
     * 获取安全的文件路径（防止路径遍历攻击）
     */
    private File getSecureFile(String path) throws SecurityException {
        if (path == null || path.trim().isEmpty()) {
            throw new SecurityException("Path cannot be empty");
        }
        
        // 移除开头的斜杠，使其成为相对路径
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        
        File file = new File(serverRoot, path);
        
        try {
            // 检查是否在服务器根目录内
            String canonicalServerRoot = serverRoot.getCanonicalPath();
            String canonicalFilePath = file.getCanonicalPath();
            
            if (!canonicalFilePath.startsWith(canonicalServerRoot)) {
                throw new SecurityException("Path traversal attack detected: " + path);
            }
            
        } catch (IOException e) {
            throw new SecurityException("Invalid path: " + path);
        }
        
        return file;
    }
    
    /**
     * 发送文件操作结果
     */
    private void sendFileOperationResult(String operationId, String operation, String path, 
                                       boolean success, String message, JsonObject data) {
        try {
            JsonObject resultMessage = new JsonObject();
            resultMessage.addProperty("type", "file_operation_result");
            
            JsonObject resultData = new JsonObject();
            resultData.addProperty("operationId", operationId);
            resultData.addProperty("operation", operation);
            resultData.addProperty("path", path);
            resultData.addProperty("success", success);
            resultData.addProperty("message", message);
            resultData.addProperty("timestamp", System.currentTimeMillis());
            
            if (data != null) {
                for (String key : data.keySet()) {
                    resultData.add(key, data.get(key));
                }
            }
            
            resultMessage.add("data", resultData);
            resultMessage.addProperty("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(resultMessage);
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("文件操作结果已发送 (ID: %s, 操作: %s, 成功: %s)", 
                operationId, operation, success));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送文件操作结果失败: " + e.getMessage());
        }
    }
}
