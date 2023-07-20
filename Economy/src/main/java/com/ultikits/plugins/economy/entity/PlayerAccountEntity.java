package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@Table("economy_account")
public class PlayerAccountEntity extends AbstractDataEntity {
    @Column(value = "cash", type = "FLOAT")
    private double cash;
}
