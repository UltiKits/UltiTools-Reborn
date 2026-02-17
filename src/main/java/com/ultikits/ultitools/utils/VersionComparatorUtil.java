package com.ultikits.ultitools.utils;

import java.util.Comparator;

/**
 * 语义版本比较工具类
 * <p>
 * 替代 hutool VersionComparator
 * <p>
 * 支持格式: 1.0.0, 1.0.0-SNAPSHOT, 1.0.0-alpha.1, 1.0.0-beta
 * 
 * @author wisdomme
 * @since 6.2.0
 */
public final class VersionComparatorUtil {
    
    /**
     * 版本比较器单例
     */
    public static final Comparator<String> COMPARATOR = VersionComparatorUtil::compare;
    
    private VersionComparatorUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    /**
     * 比较两个版本号
     *
     * @param v1 版本1
     * @param v2 版本2
     * @return 负数: v1 &lt; v2, 0: v1 == v2, 正数: v1 &gt; v2
     */
    public static int compare(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";
        
        // 去除前导 v 或 V
        v1 = stripPrefix(v1);
        v2 = stripPrefix(v2);
        
        // 分割版本号
        String[] parts1 = v1.split("[.\\-]");
        String[] parts2 = v2.split("[.\\-]");
        
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";
            
            int cmp = compareSegment(p1, p2);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
    
    /**
     * 判断 v1 是否小于 v2
     */
    public static boolean isLessThan(String v1, String v2) {
        return compare(v1, v2) < 0;
    }
    
    /**
     * 判断 v1 是否大于 v2
     */
    public static boolean isGreaterThan(String v1, String v2) {
        return compare(v1, v2) > 0;
    }
    
    /**
     * 判断 v1 是否等于 v2
     */
    public static boolean isEqual(String v1, String v2) {
        return compare(v1, v2) == 0;
    }
    
    /**
     * 去除版本号前缀
     */
    private static String stripPrefix(String version) {
        if (version.startsWith("v") || version.startsWith("V")) {
            return version.substring(1);
        }
        return version;
    }
    
    /**
     * 比较单个版本段
     * <p>
     * 数字优先，非数字按字典序
     * 特殊处理: SNAPSHOT, alpha, beta, rc 等预发布标识
     */
    private static int compareSegment(String s1, String s2) {
        // 尝试作为数字比较
        Integer n1 = parseIntOrNull(s1);
        Integer n2 = parseIntOrNull(s2);
        
        if (n1 != null && n2 != null) {
            return n1.compareTo(n2);
        }
        
        // 数字优先于非数字
        if (n1 != null) return 1;
        if (n2 != null) return -1;
        
        // 预发布版本优先级
        int p1 = getPreReleasePriority(s1);
        int p2 = getPreReleasePriority(s2);
        
        if (p1 != p2) {
            return p1 - p2;
        }
        
        // 字典序比较
        return s1.compareToIgnoreCase(s2);
    }
    
    /**
     * 解析整数，失败返回 null
     */
    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 获取预发布版本优先级
     * <p>
     * alpha &lt; beta &lt; rc &lt; SNAPSHOT &lt; (release)
     */
    private static int getPreReleasePriority(String segment) {
        String lower = segment.toLowerCase();
        if (lower.equals("alpha") || lower.startsWith("alpha")) return 1;
        if (lower.equals("beta") || lower.startsWith("beta")) return 2;
        if (lower.equals("rc") || lower.startsWith("rc")) return 3;
        if (lower.equals("snapshot")) return 4;
        return 5; // 正式版
    }
}
