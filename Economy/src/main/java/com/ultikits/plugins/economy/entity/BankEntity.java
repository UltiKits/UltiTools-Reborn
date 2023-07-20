package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false)
@Table("economy_bank")
public class BankEntity extends AbstractDataEntity {
    @Column(value = "deposit", type = "FLOAT")
    private double deposit = 0.0;
    @Column("ownerId")
    private String ownerId;
    @Column("name")
    private String name;
    @Column(value = "members", type = "JSON")
    private List<String> members = new ArrayList<>();
    @Column(value = "enable", type = "TINYINT(1)")
    private boolean enable = true;
    @Column(value = "primary", type = "TINYINT(1)")
    private boolean primary = false;

    public BankEntity(String ownerId, String name) {
        this.ownerId = ownerId;
        this.name = name;
    }

    public void increaseDeposit(double amount){
        this.deposit += amount;
    }

    public void decreaseDeposit(double amount){
        this.deposit -= amount;
    }

    public void addMember(String uuid){
        if (!members.contains(uuid)){
            members.add(uuid);
        }
    }

    public void removeMember(String uuid){
        members.remove(uuid);
    }
}
