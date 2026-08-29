package com.ultikits.ultitools.interfaces;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Localized interface.
 * <p>
 * 本地化接口
 */
public interface Localized {
    /**
     * Get the language code of the plugin module.
     * more <a href="https://en.wikipedia.org/wiki/IETF_language_tag">Language code list</a>
     * <br>
     * The returned list is derived from the {@code lang/*.json} resources the implementor's own
     * code source (its JAR, or an exploded directory in a development workspace) actually ships.
     * An empty list means no language resources were found -- not that no language is supported.
     * Overriding this method still wins over the derivation.
     * <br>
     * 获取插件模块支持的语言代码
     * 更多<a href="https://en.wikipedia.org/wiki/IETF_language_tag">语言代码列表</a>
     * <br>
     * 返回的列表派生自实现类自身代码源（其 JAR，或开发环境中展开后的目录）实际携带的
     * {@code lang/*.json} 资源。空列表代表没有找到语言资源，而不是代表不支持任何语言。
     * 重写本方法依然优先于派生结果。
     * <br><br>
     * Gets the language codes a plugin supported.
     *
     * @return 支持的语言代码 Supported language codes
     * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">Internationalization</a>
     * @since 6.3.0 derived from lang/*.json resources rather than {@code @I18n} (D-20)
     */
    default List<String> supported() {
        CodeSource codeSource = this.getClass().getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return new ArrayList<>();
        }
        return scanLangResources(codeSource.getLocation());
    }

    /**
     * Enumerates the {@code lang/*.json} resources at the given code-source location, handling
     * both a packaged JAR and an exploded directory (development workspace) layout. Reuses the
     * {@code getProtectionDomain().getCodeSource()} -&gt; {@code JarFile} idiom
     * {@code UltiToolsPlugin.saveResources()} already uses, so this is correct on a module's
     * first-ever startup, before its embedded resources have been extracted to disk.
     * <p>
     * Exposed as {@code public static} out of necessity, not invitation -- interface methods
     * cannot be non-public before Java 9, so this is a direct test seam for {@link #supported()}'s
     * derivation rather than API meant for module authors to call.
     * <br>
     * 枚举给定代码源位置下的 {@code lang/*.json} 资源，同时处理打包后的 JAR 和展开的目录
     * （开发环境）两种布局。复用了 {@code UltiToolsPlugin.saveResources()} 已经在用的
     * {@code getProtectionDomain().getCodeSource()} -&gt; {@code JarFile} 手法，因此在模块
     * 首次启动、内嵌资源尚未解压到磁盘时依然正确。
     *
     * @param codeSourceLocation the URL returned by {@code CodeSource.getLocation()}
     * @return the language codes found, sorted and de-duplicated; empty on any failure
     */
    static List<String> scanLangResources(URL codeSourceLocation) {
        try {
            String rawPath = codeSourceLocation.getPath();
            File location = new File(rawPath.startsWith("/") ? rawPath : rawPath.substring(1));
            if (location.isDirectory()) {
                return scanLangDirectory(new File(location, "lang"));
            }
            return scanLangJar(location);
        } catch (SecurityException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Lists the immediate {@code .json} children of {@code langDir} -- nested entries such as
     * {@code lang/extra/en.json} are not descended into.
     * <br>
     * 列出 {@code langDir} 的直接 {@code .json} 子文件——不会递归进入类似
     * {@code lang/extra/en.json} 这样的嵌套条目。
     *
     * @param langDir the {@code lang/} directory to scan
     * @return the language codes found; empty if {@code langDir} does not exist or is empty
     */
    static List<String> scanLangDirectory(File langDir) {
        if (langDir == null || !langDir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = langDir.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }
        Set<String> codes = new TreeSet<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".json")) {
                codes.add(name.substring(0, name.length() - ".json".length()));
            }
        }
        return new ArrayList<>(codes);
    }

    /**
     * Enumerates {@code jarFile}'s entries for immediate {@code lang/*.json} children -- neither
     * non-{@code .json} entries (e.g. {@code lang/README.txt}) nor nested entries (e.g.
     * {@code lang/extra/en.json}) are returned. No entry is extracted; this only reads names.
     * <br>
     * 枚举 {@code jarFile} 中直接位于 {@code lang/} 下的 {@code .json} 条目——非 {@code .json}
     * 条目（如 {@code lang/README.txt}）和嵌套条目（如 {@code lang/extra/en.json}）都不会被
     * 返回。不解压任何条目，只读取条目名。
     *
     * @param jarFile the module's own JAR
     * @return the language codes found; empty if {@code jarFile} cannot be opened as a JAR
     */
    static List<String> scanLangJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Set<String> codes = new TreeSet<>();
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("lang/") || !name.endsWith(".json")) {
                    continue;
                }
                String remainder = name.substring("lang/".length());
                if (remainder.isEmpty() || remainder.contains("/")) {
                    continue;
                }
                codes.add(remainder.substring(0, remainder.length() - ".json".length()));
            }
            return new ArrayList<>(codes);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns a localized string for the given default text. The {@code code} parameter is not
     * honoured by the framework's own implementation -- {@code UltiToolsPlugin} overrides this
     * method {@code final} and resolves directly against the module's already-loaded {@code
     * Language}, discarding {@code code} entirely. Language selection happens once at load time,
     * via {@link #supported()}, not per call. {@code str} is returned unchanged when it is absent
     * as a key from that already-loaded dictionary.
     * <br><br>
     * 返回给定默认文本对应的本地化字符串。框架自身的实现不会使用 {@code code} 参数——
     * {@code UltiToolsPlugin} 把本方法重写为 {@code final} 并直接基于模块已加载的
     * {@code Language} 解析，完全丢弃 {@code code}。语言的选择只在加载时通过
     * {@link #supported()} 发生一次，不是每次调用都发生。当 {@code str} 作为 key 不存在于那份
     * 已加载的字典中时，原样返回。
     *
     * @param code language code -- not honoured by the framework's own {@code i18n(String,
     *             String)} override <br> 语言代码——框架自身的 {@code i18n(String, String)}
     *             重写不会使用它
     * @param str  default display text, also the dictionary lookup key <br> 默认显示文本，同时也是字典查找的 key
     * @return a localized string, or {@code str} unchanged if the key is absent from the loaded
     *         dictionary <br> 本地化后的字符串，如果 key 不在已加载的字典中则原样返回 {@code str}
     * @since 6.3.0 corrected wording -- see D-22 (#315)
     */
    default String i18n(String code, String str) {
        return str;
    }
}
