package com.ultikits.ultitools.abstracts;

import lombok.Data;

import java.io.Serializable;

/**
 * 持久化数据抽象类
 *
 * @author wisdomme
 * @version 1.0.0
 */
@Data
public abstract class DataEntity implements Serializable {
    private Object id;
}
