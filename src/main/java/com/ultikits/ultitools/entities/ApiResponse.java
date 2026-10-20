package com.ultikits.ultitools.entities;

import lombok.Data;

/**
 * Generic wrapper for backend API envelope responses.
 * The backend wraps all plugin-related endpoints in this format.
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
