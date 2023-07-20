package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.UUID;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
@Table("economy_player_accounts")
public class AccountPlayerEntity extends AbstractDataEntity {
    @Column("playerId")
    private String playerId;
    @Column("accountId")
    private String accountId;
}
