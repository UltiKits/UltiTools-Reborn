package com.ultikits.ultitools.entities;

import lombok.Data;

import java.util.Date;


@Data
public class PluginEntity {
    private long id;
    private int developerId;
    private String name;
    private Date date;
    private Date modifiedTime;
    private String identifyString;
    private String description;
    private String shortDescription;
    private String icon;

    @Override
    public String toString() {
        return "{"
                + "\"id\":"
                + id
                + ",\"developerId\":"
                + developerId
                + ",\"name\":\""
                + name + '\"'
                + ",\"date\":\""
                + date + '\"'
                + ",\"modifiedTime\":\""
                + modifiedTime + '\"'
                + ",\"identifyString\":\""
                + identifyString + '\"'
                + ",\"description\":\""
                + description + '\"'
                + ",\"shortDescription\":\""
                + shortDescription + '\"'
                + ",\"icon\":\""
                + icon + '\"'
                + "}";
    }
}
