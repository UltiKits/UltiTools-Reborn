package com.ultikits.ultitools.entities;

import lombok.Data;

@Data
public class PluginVersionEntity {
    private long pluginId;
    private String version;
    private String downloadLink;

    @Override
    public String toString() {
        return "{"
                + "\"pluginId\":"
                + pluginId
                + ",\"version\":\""
                + version + '\"'
                + ",\"downloadLink\":\""
                + downloadLink + '\"'
                + "}";
    }
}
