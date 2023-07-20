package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("economy_accounts")
public class AccountEntity extends AbstractDataEntity {
    @Column("name")
    private String name;
    @Column(value = "balance", type = "FLOAT")
    private double balance;
    @Column("owner")
    private String owner;

    @Override
    public String toString() {
        return "{"
                + "\"id\":\""
                + super.getId() + '\"'
                + ",\"name\":\""
                + name + '\"'
                + ",\"balance\":"
                + balance
                + ",\"owner\":\""
                + owner + '\"'
                + "}";
    }
}
