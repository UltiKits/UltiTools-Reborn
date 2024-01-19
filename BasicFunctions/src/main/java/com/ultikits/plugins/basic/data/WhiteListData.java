package com.ultikits.plugins.basic.data;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Table("whitelist")
@EqualsAndHashCode(callSuper = true)
public class WhiteListData extends AbstractDataEntity {
    @Column("uuid")
    private String id;
    @Column("name")
    private String name;
    @Column("remark")
    private String remark;
}
