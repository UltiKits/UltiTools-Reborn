package com.ultikits.ultitools.abstracts;

import lombok.Data;

import java.io.Serializable;

@Data
public abstract class DataEntity implements Serializable {
    private Object id;
}
