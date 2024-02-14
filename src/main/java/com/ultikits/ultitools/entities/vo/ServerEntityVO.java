package com.ultikits.ultitools.entities.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServerEntityVO {
    private String name;
    private String uuid;
    private int port;
    private String domain;
    private boolean ssl;

    @Override
    public String toString() {
        return "{"
                + "\"name\":\""
                + name + '\"'
                + ",\"uuid\":\""
                + uuid + '\"'
                + ",\"port\":"
                + port
                + ",\"domain\":\""
                + domain + '\"'
                + ",\"ssl\":"
                + ssl
                + "}";
    }
}
