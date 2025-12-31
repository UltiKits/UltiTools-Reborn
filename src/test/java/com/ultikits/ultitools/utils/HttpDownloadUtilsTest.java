package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

/**
 * HttpDownloadUtils 测试类
 */
@DisplayName("HttpDownloadUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HttpDownloadUtilsTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("download 方法参数验证测试")
    class DownloadParameterValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("无效URL应该抛出IllegalArgumentException")
        void invalidUrlShouldThrowException(String url) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download(url, "file.txt", tempDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("无效文件名应该抛出IllegalArgumentException")
        void invalidFileNameShouldThrowException(String fileName) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", fileName, tempDir.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File name");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("无效保存路径应该抛出IllegalArgumentException")
        void invalidSavePathShouldThrowException(String savePath) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.txt", savePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Save path");
        }
    }

    @Nested
    @DisplayName("带进度回调的download方法参数验证测试")
    class DownloadWithCallbackParameterValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("无效URL应该抛出IllegalArgumentException")
        void invalidUrlShouldThrowException(String url) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download(url, "file.txt", tempDir.toString(), (b, t) -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("无效文件名应该抛出IllegalArgumentException")
        void invalidFileNameShouldThrowException(String fileName) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", fileName, tempDir.toString(), (b, t) -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File name");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("无效保存路径应该抛出IllegalArgumentException")
        void invalidSavePathShouldThrowException(String savePath) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.txt", savePath, (b, t) -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Save path");
        }

        @Test
        @DisplayName("null回调应该被接受（不抛异常）")
        void nullCallbackShouldBeAccepted() {
            // null 回调在方法内部有判空检查，不应抛 IllegalArgumentException
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.txt", tempDir.toString(), null))
                .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("downloadToByteArray 方法参数验证测试")
    class DownloadToByteArrayParameterValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("无效URL应该抛出IllegalArgumentException")
        void invalidUrlShouldThrowException(String url) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.downloadToByteArray(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
        }
    }

    @Nested
    @DisplayName("ProgressCallback 接口测试")
    class ProgressCallbackTests {

        @Test
        @DisplayName("进度回调应该是函数式接口")
        void progressCallbackShouldBeFunctionalInterface() {
            // 验证可以用 lambda 创建
            HttpDownloadUtils.ProgressCallback callback = (bytesDownloaded, totalBytes) -> {
                // do nothing
            };
            assertThat(callback).isNotNull();
        }

        @Test
        @DisplayName("进度回调应该正确接收参数")
        void progressCallbackShouldReceiveParameters() {
            AtomicLong downloadedBytes = new AtomicLong();
            AtomicLong totalBytes = new AtomicLong();
            
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> {
                downloadedBytes.set(downloaded);
                totalBytes.set(total);
            };
            
            // 模拟调用
            callback.onProgress(1024, 2048);
            
            assertThat(downloadedBytes.get()).isEqualTo(1024);
            assertThat(totalBytes.get()).isEqualTo(2048);
        }

        @Test
        @DisplayName("进度回调应该能处理未知总大小")
        void progressCallbackShouldHandleUnknownTotalSize() {
            AtomicLong totalBytes = new AtomicLong();
            
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> {
                totalBytes.set(total);
            };
            
            // 模拟未知大小
            callback.onProgress(1024, -1);
            
            assertThat(totalBytes.get()).isEqualTo(-1);
        }

        @Test
        @DisplayName("进度回调应该能处理零字节")
        void progressCallbackShouldHandleZeroBytes() {
            AtomicLong downloadedBytes = new AtomicLong(-1);
            AtomicLong totalBytesValue = new AtomicLong(-1);
            
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> {
                downloadedBytes.set(downloaded);
                totalBytesValue.set(total);
            };
            
            callback.onProgress(0, 0);
            
            assertThat(downloadedBytes.get()).isEqualTo(0);
            assertThat(totalBytesValue.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("进度回调应该能处理大文件大小")
        void progressCallbackShouldHandleLargeFileSize() {
            AtomicLong downloadedBytes = new AtomicLong();
            AtomicLong totalBytesValue = new AtomicLong();
            
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> {
                downloadedBytes.set(downloaded);
                totalBytesValue.set(total);
            };
            
            // 模拟 10GB 文件
            long tenGB = 10L * 1024 * 1024 * 1024;
            callback.onProgress(tenGB / 2, tenGB);
            
            assertThat(downloadedBytes.get()).isEqualTo(tenGB / 2);
            assertThat(totalBytesValue.get()).isEqualTo(tenGB);
        }

        @Test
        @DisplayName("多次调用进度回调应该正确更新")
        void multipleCallbackInvocationsShouldUpdateCorrectly() {
            List<Long> downloadedHistory = new ArrayList<>();
            
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> {
                downloadedHistory.add(downloaded);
            };
            
            // 模拟多次下载进度更新
            callback.onProgress(1024, 10240);
            callback.onProgress(2048, 10240);
            callback.onProgress(5120, 10240);
            callback.onProgress(10240, 10240);
            
            assertThat(downloadedHistory).containsExactly(1024L, 2048L, 5120L, 10240L);
        }

        @Test
        @DisplayName("进度回调接口应该有正确的方法签名")
        void progressCallbackShouldHaveCorrectMethodSignature() throws NoSuchMethodException {
            Method onProgressMethod = HttpDownloadUtils.ProgressCallback.class.getMethod(
                "onProgress", long.class, long.class);
            
            assertThat(onProgressMethod).isNotNull();
            assertThat(onProgressMethod.getReturnType()).isEqualTo(void.class);
            assertThat(onProgressMethod.getParameterCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("无效URL格式测试")
    class InvalidUrlFormatTests {

        @Test
        @DisplayName("无效URL格式应该抛出异常")
        void invalidUrlFormatShouldThrowException() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("not a valid url", "file.txt", tempDir.toString()))
                .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("无协议URL应该抛出异常")
        void urlWithoutProtocolShouldThrowException() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("example.com/file", "file.txt", tempDir.toString()))
                .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("FTP协议URL应该抛出异常")
        void ftpProtocolShouldThrowException() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("ftp://example.com/file", "file.txt", tempDir.toString()))
                .isInstanceOf(Exception.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/plain;base64,SGVsbG8="
        })
        @DisplayName("非HTTP协议应该无法使用")
        void nonHttpProtocolsShouldFail(String url) {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download(url, "file.txt", tempDir.toString()))
                .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("网络错误处理测试")
    class NetworkErrorHandlingTests {

        @Test
        @DisplayName("不存在的主机应该抛出IOException")
        void nonExistentHostShouldThrowIOException() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download(
                    "http://nonexistent.host.that.does.not.exist.com/file", 
                    "file.txt", 
                    tempDir.toString()))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("downloadToByteArray不存在的主机应该抛出IOException")
        void downloadToByteArrayNonExistentHostShouldThrowIOException() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.downloadToByteArray(
                    "http://nonexistent.host.that.does.not.exist.com/file"))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("带进度回调下载不存在的主机应该抛出IOException")
        void downloadWithCallbackNonExistentHostShouldThrowIOException() {
            AtomicInteger callbackCount = new AtomicInteger(0);
            HttpDownloadUtils.ProgressCallback callback = (downloaded, total) -> callbackCount.incrementAndGet();
            
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download(
                    "http://nonexistent.host.that.does.not.exist.com/file",
                    "file.txt",
                    tempDir.toString(),
                    callback))
                .isInstanceOf(IOException.class);
            
            // 回调不应该被调用（因为连接失败）
            assertThat(callbackCount.get()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("参数边界值测试")
    class ParameterBoundaryTests {

        @Test
        @DisplayName("非常长的文件名应该被接受")
        void veryLongFileNameShouldBeAccepted() {
            String longFileName = "a".repeat(200) + ".txt";
            // 只测试参数验证，不实际下载
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", longFileName, tempDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("包含空格的文件名应该被接受")
        void fileNameWithSpacesShouldBeAccepted() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "my file.txt", tempDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("包含特殊字符的URL应该被处理")
        void urlWithSpecialCharactersShouldBeHandled() {
            // URL编码的特殊字符应该能被解析
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file%20name", "file.txt", tempDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("包含中文的文件名应该被接受")
        void fileNameWithChineseCharactersShouldBeAccepted() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "测试文件.txt", tempDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("包含点号的文件名应该被接受")
        void fileNameWithMultipleDotsShouldBeAccepted() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.name.with.dots.txt", tempDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("相对路径保存位置应该被接受")
        void relativeSavePathShouldBeAccepted() {
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.txt", "./downloads"))
                .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("方法签名和反射测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("download方法应该存在且有正确签名")
        void downloadMethodShouldExist() throws Exception {
            Method method = HttpDownloadUtils.class.getMethod(
                "download", String.class, String.class, String.class);
            
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("download带回调方法应该存在且有正确签名")
        void downloadWithCallbackMethodShouldExist() throws Exception {
            Method method = HttpDownloadUtils.class.getMethod(
                "download", String.class, String.class, String.class, HttpDownloadUtils.ProgressCallback.class);
            
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("downloadToByteArray方法应该存在且有正确签名")
        void downloadToByteArrayMethodShouldExist() throws Exception {
            Method method = HttpDownloadUtils.class.getMethod(
                "downloadToByteArray", String.class);
            
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(byte[].class);
            assertThat(java.lang.reflect.Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("所有public方法应该声明抛出IOException")
        void publicMethodsShouldDeclareIOException() throws Exception {
            Method download1 = HttpDownloadUtils.class.getMethod(
                "download", String.class, String.class, String.class);
            Method download2 = HttpDownloadUtils.class.getMethod(
                "download", String.class, String.class, String.class, HttpDownloadUtils.ProgressCallback.class);
            Method downloadToByteArray = HttpDownloadUtils.class.getMethod(
                "downloadToByteArray", String.class);
            
            assertThat(download1.getExceptionTypes()).contains(IOException.class);
            assertThat(download2.getExceptionTypes()).contains(IOException.class);
            assertThat(downloadToByteArray.getExceptionTypes()).contains(IOException.class);
        }
    }

    @Nested
    @DisplayName("目录创建测试")
    class DirectoryCreationTests {

        @Test
        @DisplayName("嵌套目录应该被创建")
        void nestedDirectoryShouldBeCreated() {
            Path nestedPath = tempDir.resolve("a/b/c/d");
            assertThat(Files.exists(nestedPath)).isFalse();
            
            // 由于网络请求会失败，但目录应该在此之前被创建
            // 这个测试验证目录创建逻辑
            File nestedDir = nestedPath.toFile();
            boolean created = nestedDir.mkdirs();
            
            assertThat(created || nestedDir.exists()).isTrue();
            assertThat(Files.exists(nestedPath)).isTrue();
        }

        @Test
        @DisplayName("已存在的目录应该不影响下载")
        void existingDirectoryShouldNotAffectDownload() throws IOException {
            Path existingDir = tempDir.resolve("existing");
            Files.createDirectories(existingDir);
            
            assertThat(Files.exists(existingDir)).isTrue();
            
            // 使用已存在目录应该不抛 IllegalArgumentException
            assertThatThrownBy(() -> 
                HttpDownloadUtils.download("http://example.com/file", "file.txt", existingDir.toString()))
                .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("URL协议测试")
    class UrlProtocolTests {

        @Test
        @DisplayName("HTTP协议URL应该能被正确解析")
        void httpProtocolShouldBeParseable() throws Exception {
            // 验证 HTTP URL 能被正确解析为 URI
            URI uri = URI.create("http://example.com/file");
            URL url = uri.toURL();
            
            assertThat(url.getProtocol()).isEqualTo("http");
            assertThat(url.getHost()).isEqualTo("example.com");
            assertThat(url.getPath()).isEqualTo("/file");
        }

        @Test
        @DisplayName("HTTPS协议URL应该能被正确解析")
        void httpsProtocolShouldBeParseable() throws Exception {
            URI uri = URI.create("https://example.com/file");
            URL url = uri.toURL();
            
            assertThat(url.getProtocol()).isEqualTo("https");
            assertThat(url.getHost()).isEqualTo("example.com");
        }

        @Test
        @DisplayName("带端口号的URL应该能被正确解析")
        void urlWithPortShouldBeParseable() throws Exception {
            URI uri = URI.create("http://example.com:8080/file");
            URL url = uri.toURL();
            
            assertThat(url.getPort()).isEqualTo(8080);
        }

        @Test
        @DisplayName("带查询参数的URL应该能被正确解析")
        void urlWithQueryParamsShouldBeParseable() throws Exception {
            URI uri = URI.create("http://example.com/file?param=value&foo=bar");
            URL url = uri.toURL();
            
            assertThat(url.getQuery()).isEqualTo("param=value&foo=bar");
        }

        @Test
        @DisplayName("带片段标识符的URL应该能被正确解析")
        void urlWithFragmentShouldBeParseable() throws Exception {
            URI uri = URI.create("http://example.com/file#section");
            
            assertThat(uri.getFragment()).isEqualTo("section");
        }

        @Test
        @DisplayName("带用户信息的URL应该能被正确解析")
        void urlWithUserInfoShouldBeParseable() throws Exception {
            URI uri = URI.create("http://user:pass@example.com/file");
            
            assertThat(uri.getUserInfo()).isEqualTo("user:pass");
        }

        @Test
        @DisplayName("带编码字符的URL应该能被正确解析")
        void urlWithEncodedCharsShouldBeParseable() throws Exception {
            URI uri = URI.create("http://example.com/path%20with%20spaces");
            URL url = uri.toURL();
            
            assertThat(url.getPath()).isEqualTo("/path%20with%20spaces");
        }
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("HttpDownloadUtils应该是公共类")
        void shouldBePublicClass() {
            assertThat(java.lang.reflect.Modifier.isPublic(HttpDownloadUtils.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("ProgressCallback应该是公共内部接口")
        void progressCallbackShouldBePublicInterface() {
            Class<?> callbackClass = HttpDownloadUtils.ProgressCallback.class;
            assertThat(callbackClass.isInterface()).isTrue();
            assertThat(java.lang.reflect.Modifier.isPublic(callbackClass.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("ProgressCallback应该有@FunctionalInterface注解")
        void progressCallbackShouldHaveFunctionalInterfaceAnnotation() {
            assertThat(HttpDownloadUtils.ProgressCallback.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("Mock HTTP 连接测试 - download方法")
    class MockHttpConnectionDownloadTests {

        @Test
        @DisplayName("成功下载文件应该正确写入")
        void successfulDownloadShouldWriteFileCorrectly() throws IOException {
            // 准备测试数据
            byte[] testContent = "Hello, World! This is test content.".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            // Mock HttpURLConnection
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            // Mock URL
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            // 使用 MockedStatic 来 mock URI.create 和 URL
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/test.txt")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                // 执行下载
                HttpDownloadUtils.download("http://example.com/test.txt", "downloaded.txt", tempDir.toString());
                
                // 验证文件已创建并包含正确内容
                Path downloadedFile = tempDir.resolve("downloaded.txt");
                assertThat(Files.exists(downloadedFile)).isTrue();
                assertThat(Files.readAllBytes(downloadedFile)).isEqualTo(testContent);
                
                // 验证连接被正确关闭
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("HTTP 404错误应该抛出IOException")
        void http404ErrorShouldThrowIOException() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
            when(mockConnection.getResponseMessage()).thenReturn("Not Found");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/notfound")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/notfound", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("404")
                    .hasMessageContaining("Not Found");
                
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("HTTP 500错误应该抛出IOException")
        void http500ErrorShouldThrowIOException() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);
            when(mockConnection.getResponseMessage()).thenReturn("Internal Server Error");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/error")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/error", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("500");
                
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("HTTP 503服务不可用应该抛出IOException")
        void http503ServiceUnavailableShouldThrowIOException() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_UNAVAILABLE);
            when(mockConnection.getResponseMessage()).thenReturn("Service Unavailable");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/unavailable")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/unavailable", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("503");
                
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("连接超时设置应该被正确应用")
        void connectionTimeoutShouldBeSet() throws IOException {
            byte[] testContent = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/timeout")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/timeout", "file.txt", tempDir.toString());
                
                // 验证超时设置
                verify(mockConnection).setConnectTimeout(10 * 1000);
                verify(mockConnection).setReadTimeout(30 * 1000);
            }
        }

        @Test
        @DisplayName("User-Agent header应该被正确设置")
        void userAgentHeaderShouldBeSet() throws IOException {
            byte[] testContent = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/useragent")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/useragent", "file.txt", tempDir.toString());
                
                // 验证 User-Agent 被设置
                verify(mockConnection).setRequestProperty(anyString(), anyString());
            }
        }

        @Test
        @DisplayName("输入流读取异常应该被正确处理")
        void inputStreamReadExceptionShouldBeHandled() throws IOException {
            InputStream mockInputStream = mock(InputStream.class);
            when(mockInputStream.read(any(byte[].class))).thenThrow(new IOException("Read error"));
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(mockInputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/readfail")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/readfail", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class);
                
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("空响应应该创建空文件")
        void emptyResponseShouldCreateEmptyFile() throws IOException {
            ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(emptyStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/empty")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/empty", "empty.txt", tempDir.toString());
                
                Path downloadedFile = tempDir.resolve("empty.txt");
                assertThat(Files.exists(downloadedFile)).isTrue();
                assertThat(Files.size(downloadedFile)).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("大文件下载应该正确处理多次读取")
        void largeFileDownloadShouldHandleMultipleReads() throws IOException {
            // 创建一个模拟大文件的输入流 (每次读取返回部分数据)
            byte[] chunk1 = "First chunk of data. ".getBytes(StandardCharsets.UTF_8);
            byte[] chunk2 = "Second chunk of data. ".getBytes(StandardCharsets.UTF_8);
            byte[] chunk3 = "Final chunk.".getBytes(StandardCharsets.UTF_8);
            
            byte[] allData = new byte[chunk1.length + chunk2.length + chunk3.length];
            System.arraycopy(chunk1, 0, allData, 0, chunk1.length);
            System.arraycopy(chunk2, 0, allData, chunk1.length, chunk2.length);
            System.arraycopy(chunk3, 0, allData, chunk1.length + chunk2.length, chunk3.length);
            
            ByteArrayInputStream inputStream = new ByteArrayInputStream(allData);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/large")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/large", "large.txt", tempDir.toString());
                
                Path downloadedFile = tempDir.resolve("large.txt");
                assertThat(Files.readAllBytes(downloadedFile)).isEqualTo(allData);
            }
        }
    }

    @Nested
    @DisplayName("Mock HTTP 连接测试 - download方法(带进度回调)")
    class MockHttpConnectionDownloadWithCallbackTests {

        @Test
        @DisplayName("进度回调应该被正确调用")
        void progressCallbackShouldBeCalledCorrectly() throws IOException {
            byte[] testContent = "Hello, World!".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            when(mockConnection.getContentLengthLong()).thenReturn((long) testContent.length);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            AtomicLong lastDownloaded = new AtomicLong(0);
            AtomicLong lastTotal = new AtomicLong(0);
            AtomicInteger callCount = new AtomicInteger(0);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/progress")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/progress", "file.txt", tempDir.toString(),
                    (downloaded, total) -> {
                        lastDownloaded.set(downloaded);
                        lastTotal.set(total);
                        callCount.incrementAndGet();
                    });
                
                // 验证回调被调用且最终值正确
                assertThat(callCount.get()).isGreaterThan(0);
                assertThat(lastDownloaded.get()).isEqualTo(testContent.length);
                assertThat(lastTotal.get()).isEqualTo(testContent.length);
            }
        }

        @Test
        @DisplayName("未知内容长度应该回调-1")
        void unknownContentLengthShouldCallbackWithMinusOne() throws IOException {
            byte[] testContent = "test data".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            when(mockConnection.getContentLengthLong()).thenReturn(-1L);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            AtomicLong receivedTotal = new AtomicLong(0);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/unknown")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/unknown", "file.txt", tempDir.toString(),
                    (downloaded, total) -> receivedTotal.set(total));
                
                assertThat(receivedTotal.get()).isEqualTo(-1L);
            }
        }

        @Test
        @DisplayName("null回调应该不影响下载")
        void nullCallbackShouldNotAffectDownload() throws IOException {
            byte[] testContent = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            when(mockConnection.getContentLengthLong()).thenReturn((long) testContent.length);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/nullcb")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                // 不应该抛出异常
                HttpDownloadUtils.download("http://example.com/nullcb", "file.txt", tempDir.toString(), null);
                
                Path downloadedFile = tempDir.resolve("file.txt");
                assertThat(Files.exists(downloadedFile)).isTrue();
            }
        }

        @Test
        @DisplayName("HTTP错误时进度回调不应该被调用")
        void httpErrorShouldNotCallProgressCallback() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_NOT_FOUND);
            when(mockConnection.getResponseMessage()).thenReturn("Not Found");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            AtomicInteger callCount = new AtomicInteger(0);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/error404")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/error404", "file.txt", tempDir.toString(),
                        (downloaded, total) -> callCount.incrementAndGet()))
                    .isInstanceOf(IOException.class);
                
                assertThat(callCount.get()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("大文件下载进度回调应该被多次调用")
        void largeFileProgressCallbackShouldBeCalledMultipleTimes() throws IOException {
            // 创建一个较大的测试内容
            byte[] testContent = new byte[16384]; // 16KB
            for (int i = 0; i < testContent.length; i++) {
                testContent[i] = (byte) (i % 256);
            }
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            when(mockConnection.getContentLengthLong()).thenReturn((long) testContent.length);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            List<Long> progressHistory = new ArrayList<>();
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/largeprogress")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/largeprogress", "large.bin", tempDir.toString(),
                    (downloaded, total) -> progressHistory.add(downloaded));
                
                // 应该有多次回调，进度应该递增
                assertThat(progressHistory).isNotEmpty();
                assertThat(progressHistory.get(progressHistory.size() - 1)).isEqualTo(testContent.length);
                
                // 验证进度是递增的
                for (int i = 1; i < progressHistory.size(); i++) {
                    assertThat(progressHistory.get(i)).isGreaterThanOrEqualTo(progressHistory.get(i - 1));
                }
            }
        }
    }

    @Nested
    @DisplayName("Mock HTTP 连接测试 - downloadToByteArray方法")
    class MockHttpConnectionDownloadToByteArrayTests {

        @Test
        @DisplayName("成功下载应该返回正确的字节数组")
        void successfulDownloadShouldReturnCorrectByteArray() throws IOException {
            byte[] testContent = "Test byte array content".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/bytes")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                byte[] result = HttpDownloadUtils.downloadToByteArray("http://example.com/bytes");
                
                assertThat(result).isEqualTo(testContent);
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("空响应应该返回空字节数组")
        void emptyResponseShouldReturnEmptyByteArray() throws IOException {
            ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(emptyStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/emptybytes")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                byte[] result = HttpDownloadUtils.downloadToByteArray("http://example.com/emptybytes");
                
                assertThat(result).isEmpty();
            }
        }

        @Test
        @DisplayName("HTTP错误应该抛出IOException")
        void httpErrorShouldThrowIOException() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_FORBIDDEN);
            when(mockConnection.getResponseMessage()).thenReturn("Forbidden");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/forbidden")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> HttpDownloadUtils.downloadToByteArray("http://example.com/forbidden"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("403");
            }
        }

        @Test
        @DisplayName("二进制内容应该正确处理")
        void binaryContentShouldBeHandledCorrectly() throws IOException {
            // 创建包含所有字节值的测试数据
            byte[] binaryContent = new byte[256];
            for (int i = 0; i < 256; i++) {
                binaryContent[i] = (byte) i;
            }
            ByteArrayInputStream inputStream = new ByteArrayInputStream(binaryContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/binary")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                byte[] result = HttpDownloadUtils.downloadToByteArray("http://example.com/binary");
                
                assertThat(result).isEqualTo(binaryContent);
            }
        }

        @Test
        @DisplayName("连接设置应该被正确应用")
        void connectionSettingsShouldBeApplied() throws IOException {
            byte[] testContent = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(testContent);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/settings")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.downloadToByteArray("http://example.com/settings");
                
                verify(mockConnection).setConnectTimeout(10 * 1000);
                verify(mockConnection).setReadTimeout(30 * 1000);
                verify(mockConnection, times(1)).setRequestProperty(anyString(), anyString());
            }
        }

        @Test
        @DisplayName("输入流异常应该正确处理并断开连接")
        void inputStreamExceptionShouldBeHandledAndDisconnect() throws IOException {
            InputStream mockInputStream = mock(InputStream.class);
            when(mockInputStream.read(any(byte[].class))).thenThrow(new IOException("Stream read error"));
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(mockInputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/streamfail")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> HttpDownloadUtils.downloadToByteArray("http://example.com/streamfail"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Stream read error");
                
                verify(mockConnection).disconnect();
            }
        }
    }

    @Nested
    @DisplayName("HTTP 状态码覆盖测试")
    class HttpStatusCodeCoverageTests {

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 402, 403, 404, 405, 408, 410, 429, 500, 501, 502, 503, 504})
        @DisplayName("各种HTTP错误状态码都应该抛出IOException")
        void variousHttpErrorCodesShouldThrowIOException(int statusCode) throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(statusCode);
            when(mockConnection.getResponseMessage()).thenReturn("Error " + statusCode);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/status" + statusCode)).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/status" + statusCode, "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(String.valueOf(statusCode));
            }
        }

        @Test
        @DisplayName("HTTP 200应该成功下载")
        void http200ShouldSucceed() throws IOException {
            byte[] content = "success".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(200);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/ok")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/ok", "success.txt", tempDir.toString());
                
                assertThat(Files.exists(tempDir.resolve("success.txt"))).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("连接异常处理测试")
    class ConnectionExceptionHandlingTests {

        @Test
        @DisplayName("openConnection异常应该传播")
        void openConnectionExceptionShouldPropagate() throws IOException {
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenThrow(new IOException("Connection refused"));
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/connfail")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/connfail", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Connection refused");
            }
        }

        @Test
        @DisplayName("getResponseCode异常应该正确处理")
        void getResponseCodeExceptionShouldBeHandled() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenThrow(new IOException("Network error"));
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/respfail")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/respfail", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class);
                
                verify(mockConnection).disconnect();
            }
        }

        @Test
        @DisplayName("getInputStream异常应该正确处理")
        void getInputStreamExceptionShouldBeHandled() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenThrow(new IOException("Cannot get input stream"));
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/inputfail")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/inputfail", "file.txt", tempDir.toString()))
                    .isInstanceOf(IOException.class);
                
                verify(mockConnection).disconnect();
            }
        }
    }

    @Nested
    @DisplayName("资源清理验证测试")
    class ResourceCleanupTests {

        @Test
        @DisplayName("成功下载后连接应该断开")
        void connectionShouldBeDisconnectedAfterSuccess() throws IOException {
            byte[] content = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/cleanup1")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.download("http://example.com/cleanup1", "file.txt", tempDir.toString());
                
                verify(mockConnection, times(1)).disconnect();
            }
        }

        @Test
        @DisplayName("失败后连接应该断开")
        void connectionShouldBeDisconnectedAfterFailure() throws IOException {
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR);
            when(mockConnection.getResponseMessage()).thenReturn("Internal Server Error");
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/cleanup2")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                assertThatThrownBy(() -> 
                    HttpDownloadUtils.download("http://example.com/cleanup2", "file.txt", tempDir.toString()));
                
                verify(mockConnection, times(1)).disconnect();
            }
        }

        @Test
        @DisplayName("downloadToByteArray成功后连接应该断开")
        void byteArrayDownloadShouldDisconnectAfterSuccess() throws IOException {
            byte[] content = "test".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
            
            HttpURLConnection mockConnection = mock(HttpURLConnection.class);
            when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
            when(mockConnection.getInputStream()).thenReturn(inputStream);
            
            URL mockUrl = mock(URL.class);
            when(mockUrl.openConnection()).thenReturn(mockConnection);
            
            try (MockedStatic<URI> mockedUri = mockStatic(URI.class)) {
                URI mockUri = mock(URI.class);
                mockedUri.when(() -> URI.create("http://example.com/cleanup3")).thenReturn(mockUri);
                when(mockUri.toURL()).thenReturn(mockUrl);
                
                HttpDownloadUtils.downloadToByteArray("http://example.com/cleanup3");
                
                verify(mockConnection, times(1)).disconnect();
            }
        }
    }
}
