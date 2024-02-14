package com.ultikits.ultitools.entities;

import lombok.Data;

@Data
public class TokenEntity {
    private String access_token;
    private String refresh_token;
    private String id;
    private String token_type;
    private int expires_in;
    private String scope;
    private String jti;
}
