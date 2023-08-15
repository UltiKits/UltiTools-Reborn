package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

@Data
@EqualsAndHashCode(callSuper = false)
@Table("economy_cash")
public class PlayerAccountEntity extends AbstractDataEntity {
    @Column(value = "cash", type = "FLOAT")
    private double cash;

    @Override
    public String toString() {
        return "{"
                + "\"cash\":"
                + cash
                + "}";
    }
}
