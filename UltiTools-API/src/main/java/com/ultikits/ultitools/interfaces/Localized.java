package com.ultikits.ultitools.interfaces;

import java.util.ArrayList;
import java.util.List;

public interface Localized {
    /**
     * 获取插件模块支持的语言代码
     * <p></p>
     * 更多<a href="https://en.wikipedia.org/wiki/IETF_language_tag">语言代码列表</a>
     * <p></p>
     * Gets the language codes a plugin supported.
     * <p></p>
     * Check <a href="https://en.wikipedia.org/wiki/IETF_language_tag">language code list</a>
     *
     * @return 支持的语言代码 Supported language codes
     */
    default List<String> supported() {
        return new ArrayList<>();
    }

    /**
     * 通过指定的语言代码返回一个本地化的字符串。如果语言代码没有出现在{@link #supported}里面，则返回传入的原文。
     * <p></p>
     * Returns a localized string that indicated by user. If the chosen language code is not
     * presented in {@link #supported}, this method will return the text by default.
     *
     * @param code 语言代码 language code
     * @param str  默认的显示文本 default display text
     * @return 一个本地化的字符串，如果语言代码不被支持则返回原文 A localized string, or the default text if the localization is not supported
     */
    default String i18n(String code, String str) {
        return str;
    }
}
