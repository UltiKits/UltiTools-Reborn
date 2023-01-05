package com.ultikits.ultitools.webserver.wrapper;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResultWrapper<T> {
    private int code;
    private String msg;
    private T data;

    public static ResultWrapper<String> success(){
        return new ResultWrapper<>(200, "ok", null);
    }

    public static <T> ResultWrapper<T> success(T data){
        return new ResultWrapper<>(200, "ok", data);
    }
}
