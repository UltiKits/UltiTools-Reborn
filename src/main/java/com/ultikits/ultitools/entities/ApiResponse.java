package com.ultikits.ultitools.entities;

import lombok.Data;

/**
 * Generic wrapper for backend API envelope responses.
 * The backend wraps all plugin-related endpoints in this format.
 * <br>
 * 后端API信封响应的通用包装类。
 * 后端将所有插件相关端点包装在此格式中。
 *
 * @param <T> the type of the data payload
 * @author wisdomme
 * @since 6.2.0
 */
@Data
public class ApiResponse<T> {
    private String code;
    private String msg;
    private T data;
}
