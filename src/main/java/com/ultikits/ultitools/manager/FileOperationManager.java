package com.ultikits.ultitools.manager;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ultikits.ultitools.UltiTools;
import com.ultikits.ultitools.websocket.UltiPanelWebSocketClient;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 文件操作管理器
 * 负责处理来自WebSocket的文件操作请求
 */
public class FileOperationManager {
    private UltiPanelWebSocketClient webSocketClient;
    private final File serverRoot;
    
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
     * 处理文件操作请求
     */
    public void handleFileOperation(JSONObject operationData) {
        try {
            String operation = operationData.getString("operation");
            String path = operationData.getString("path");
            String operationId = operationData.getString("operationId");
            
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
            String operationId = operationData.getString("operationId");
            UltiTools.getInstance().getLogger().log(Level.WARNING, "文件操作处理失败: " + e.getMessage());
            sendFileOperationResult(operationId, "unknown", "unknown", false, 
                "Error processing file operation: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件读取操作
     */
    private void handleReadOperation(String path, JSONObject operationData, String operationId) {
        try {
            File file = getSecureFile(path);
            if (!file.exists()) {
                sendFileOperationResult(operationId, "read", path, false, "File not found", null);
                return;
            }
            
            if (!file.isFile()) {
                sendFileOperationResult(operationId, "read", path, false, "Path is not a file", null);
                return;
            }
            
            int limit = operationData.getIntValue("limit");
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
            
            JSONObject resultData = new JSONObject();
            resultData.put("content", content.toString().trim());
            resultData.put("size", file.length());
            resultData.put("lastModified", file.lastModified());
            resultData.put("linesRead", content.toString().split("\n").length);
            
            sendFileOperationResult(operationId, "read", path, true, "File read successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "read", path, false, 
                "Error reading file: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件写入操作
     */
    private void handleWriteOperation(String path, JSONObject operationData, String operationId) {
        try {
            File file = getSecureFile(path);
            String content = operationData.getString("content");
            boolean append = operationData.getBooleanValue("append");
            
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
            
            JSONObject resultData = new JSONObject();
            resultData.put("bytesWritten", content.getBytes().length);
            resultData.put("fileSize", file.length());
            
            sendFileOperationResult(operationId, "write", path, true, "File written successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "write", path, false, 
                "Error writing file: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理目录列表操作
     */
    private void handleListOperation(String path, JSONObject operationData, String operationId) {
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
            
            JSONArray fileList = new JSONArray();
            for (File file : files) {
                JSONObject fileInfo = new JSONObject();
                fileInfo.put("name", file.getName());
                fileInfo.put("isDirectory", file.isDirectory());
                fileInfo.put("size", file.isDirectory() ? 0 : file.length());
                fileInfo.put("lastModified", file.lastModified());
                fileInfo.put("readable", file.canRead());
                fileInfo.put("writable", file.canWrite());
                fileList.add(fileInfo);
            }
            
            JSONObject resultData = new JSONObject();
            resultData.put("files", fileList);
            resultData.put("totalCount", fileList.size());
            
            sendFileOperationResult(operationId, "list", path, true, "Directory listed successfully", resultData);
            
        } catch (Exception e) {
            sendFileOperationResult(operationId, "list", path, false, 
                "Error listing directory: " + e.getMessage(), null);
        }
    }
    
    /**
     * 处理文件删除操作
     */
    private void handleDeleteOperation(String path, JSONObject operationData, String operationId) {
        try {
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
                                       boolean success, String message, JSONObject data) {
        try {
            JSONObject resultMessage = new JSONObject();
            resultMessage.put("type", "file_operation_result");
            
            JSONObject resultData = new JSONObject();
            resultData.put("operationId", operationId);
            resultData.put("operation", operation);
            resultData.put("path", path);
            resultData.put("success", success);
            resultData.put("message", message);
            resultData.put("timestamp", System.currentTimeMillis());
            
            if (data != null) {
                for (String key : data.keySet()) {
                    resultData.put(key, data.get(key));
                }
            }
            
            resultMessage.put("data", resultData);
            resultMessage.put("serverId", webSocketClient.getServerId());
            
            webSocketClient.sendMessage(resultMessage);
            
            UltiTools.getInstance().getLogger().log(Level.INFO, 
                String.format("文件操作结果已发送 (ID: %s, 操作: %s, 成功: %s)", 
                operationId, operation, success));
            
        } catch (Exception e) {
            UltiTools.getInstance().getLogger().log(Level.WARNING, "发送文件操作结果失败: " + e.getMessage());
        }
    }
}
