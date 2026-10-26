package com.ultikits.ultitools.entities.vo;

import lombok.Builder;
import lombok.Data;

/**
 * The server record the protocol v1 cloud registration sent. As of 6.3.1 nothing in the framework
 * uses it: protocol 2.0.0 links a server through the device authorization instead.
 *
 * @deprecated There is no replacement: {@code /ulticloud login} links a protocol 2.0.0 server.
 *     Removed in 6.4.0.
 * @removeIn 6.4.0
 */
@Data
@Builder
@Deprecated(since = "6.3.1", forRemoval = true)
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
