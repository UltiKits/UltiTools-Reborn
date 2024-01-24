package com.ultikits.ultitools.interfaces;

import com.ultikits.ultitools.annotations.I18n;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * 获取插件模块支持的语言代码
     * 更多<a href="https://en.wikipedia.org/wiki/IETF_language_tag">语言代码列表</a>
     * <br><br>
     * Gets the language codes a plugin supported.
     *
     * @return 支持的语言代码 Supported language codes
     * @see <a href="https://dev.ultikits.com/en/guide/essentials/i18n.html">Internationalization</a>
     */
    default List<String> supported() {
        I18n i18n = AnnotationUtils.findAnnotation(this.getClass(), I18n.class);
        if (i18n != null) {
            return Arrays.asList(i18n.value());
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Returns a localized string that indicated by user. If the chosen language code is not
     * presented in {@link #supported}, this method will return the text by default.
     * <br><br>
     * 通过指定的语言代码返回一个本地化的字符串。如果语言代码没有出现在{@link #supported}里面，则返回传入的原文。
     *
     * @param code language code <br> 语言代码
     * @param str  default display text <br> 默认的显示文本
     * @return A localized string, or the default text if the localization is not supported<br>一个本地化的字符串，如果语言代码不被支持则返回原文
     */
    default String i18n(String code, String str) {
        return str;
    }
}
