package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.entities.PluginEntity;
import com.ultikits.ultitools.stub.StubCatalogue;

/**
 * PluginInstallUtils 测试类
 * 测试插件安装工具类的方法签名和结构
 */
@DisplayName("PluginInstallUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginInstallUtilsTest {

    /** The version shape, naming the given version of UltiTrade with an absolute download URL on {@code base}. */
    private static String version(String base, String version) {
        return StubCatalogue.versionSample(version,
                base + "/v1/catalogue/modules/ultitrade/versions/" + version + "/download");
    }

    @Test
    @DisplayName("每个元数据请求都把标识规范化为模块引用、把版本编码成路径段，且只有版本列表带级别过滤")
    void normalizesAndEncodesLookupForAllMetadataPaths() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> versionPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/v1/catalogue/modules/ultitrade", exchange -> {
            request.set(exchange.getRequestURI().getRawQuery());
            byte[] body = StubCatalogue.moduleCardSample("ultitrade", "UltiTrade")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/catalogue/modules/ultitrade/versions", exchange -> {
            byte[] body = StubCatalogue.versionsPageOf(version(base, "1.0.0")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/catalogue/modules/ultitrade/versions/", exchange -> {
            versionPath.set(exchange.getRequestURI().getRawPath());
            byte[] body = ("{\"data\":" + version(base, "release candidate") + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            PluginInstallUtils.setBaseUrlForTesting(base);
            assertThat(PluginInstallUtils.getPluginLatestVersion("  UltiTrade  ")).isEqualTo("1.0.0");
            // The module route takes no level parameter (the contract rejects undeclared ones).
            assertThat(request.get()).isNull();
            // An identify-string that cannot be a module reference is refused without a request.
            assertThat(PluginInstallUtils.getPlugin(" bad name+/")).isNull();
            assertThat(PluginInstallUtils.getPluginVersionDownloadLink("UltiTrade", "release candidate"))
                    .isEqualTo("http://localhost:" + server.getAddress().getPort()
                            + "/v1/catalogue/modules/ultitrade/versions/release candidate/download");
            assertThat(versionPath.get()).isEqualTo("/v1/catalogue/modules/ultitrade/versions/release%20candidate");
            assertThat(PluginInstallUtils.getPlugin(null)).isNull();
            assertThat(PluginInstallUtils.getPlugin("  ")).isNull();
        } finally {
            PluginInstallUtils.resetBaseUrl();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("应构造确定且路径安全的模块 JAR 文件名")
    void constructsDeterministicSafeJarNames() {
        assertThat(PluginInstallUtils.installedJarName(" UltiTrade ", "1.0.0-SNAPSHOT")).isEqualTo("ultitrade-1.0.0-SNAPSHOT.jar");
        assertThat(PluginInstallUtils.installedJarName("../../escape", "../bad")).isNull();
        assertThat(PluginInstallUtils.installedJarName(null, "1.0.0")).isNull();
    }

    @Test
    @DisplayName("应使用规范化标识查找既有模块 JAR")
    void findsExistingPluginJarUsingCanonicalIdentifyString(@TempDir Path pluginsFolder) throws Exception {
        Path jar = pluginsFolder.resolve("artifact.jar");
        try (OutputStream output = java.nio.file.Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output)) {
            jarOutput.putNextEntry(new JarEntry("plugin.yml"));
            jarOutput.write("identify-string: ultitrade\n".getBytes(StandardCharsets.UTF_8));
            jarOutput.closeEntry();
        }

        assertThat(PluginInstallUtils.findPluginJar(pluginsFolder.toFile(), " UltiTrade "))
                .isEqualTo(jar.toFile());
    }

    @Test
    @DisplayName("不可读 JAR 不应中断模块目录扫描")
    void skipsUnreadablePluginJars(@TempDir Path pluginsFolder) throws Exception {
        java.nio.file.Files.write(pluginsFolder.resolve("broken.jar"), "not-a-jar".getBytes(StandardCharsets.UTF_8));

        assertThat(PluginInstallUtils.findPluginJar(pluginsFolder.toFile(), "UltiTrade")).isNull();
    }

    @Nested
    @DisplayName("类结构测试")
    class ClassStructureTests {

        @Test
        @DisplayName("类应该是public的")
        void classShouldBePublic() {
            assertThat(Modifier.isPublic(PluginInstallUtils.class.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("方法签名测试")
    class MethodSignatureTests {

        @Test
        @DisplayName("getPluginList方法应该存在")
        void getPluginListMethodShouldExist() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(List.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("getPlugin方法应该存在")
        void getPluginMethodShouldExist() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            assertThat(method).isNotNull();
            assertThat(method.getReturnType()).isEqualTo(PluginEntity.class);
            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(method.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("参数验证测试")
    class ParameterValidationTests {

        @Test
        @DisplayName("getPluginList应该接受正确的参数类型")
        void getPluginListShouldAcceptCorrectParameterTypes() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            Class<?>[] paramTypes = method.getParameterTypes();
            assertThat(paramTypes).containsExactly(int.class, int.class);
        }

        @Test
        @DisplayName("getPlugin应该接受正确的参数类型")
        void getPluginShouldAcceptCorrectParameterTypes() throws Exception {
            Method method = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            Class<?>[] paramTypes = method.getParameterTypes();
            assertThat(paramTypes).containsExactly(String.class);
        }
    }

    @Nested
    @DisplayName("PluginEntity 结构测试")
    class PluginEntityTests {

        @Test
        @DisplayName("PluginEntity类应该存在")
        void pluginEntityClassShouldExist() {
            assertThat(PluginEntity.class).isNotNull();
        }

        @Test
        @DisplayName("PluginEntity应该有正确的字段")
        void pluginEntityShouldHaveCorrectFields() throws Exception {
            assertThat(PluginEntity.class.getDeclaredField("id")).isNotNull();
            assertThat(PluginEntity.class.getDeclaredField("name")).isNotNull();
        }
    }

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("方法不应该声明受检异常")
        void methodsShouldNotDeclareCheckedExceptions() throws Exception {
            Method getPluginList = PluginInstallUtils.class.getDeclaredMethod("getPluginList", int.class, int.class);
            Method getPlugin = PluginInstallUtils.class.getDeclaredMethod("getPlugin", String.class);
            
            assertThat(getPluginList.getExceptionTypes()).isEmpty();
            assertThat(getPlugin.getExceptionTypes()).isEmpty();
        }
    }
}
