package com.ultikits.plugins.economy.entity;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@Builder
@EqualsAndHashCode(callSuper = false)
@Table("economy_transaction_records")
public class TransactionRecordEntity extends AbstractDataEntity {

    @Builder.Default
    @Column(value = "createTime", type = "BIGINT")
    private long createTime = new Date().getTime();
    @Column(value = "finishTime", type = "BIGINT")
    private long finishTime;
    @Column("payerId")
    private String payerId;
    @Column("receiverId")
    private String receiverId;
    @Column(value = "amount", type = "FLOUT")
    private double amount;
    @Column(value = "status", type = "TINYINT(1)")
    private boolean status;
}
