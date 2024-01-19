package com.ultikits.plugins.basic.data;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.entities.common.WorldLocation;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Data
@Table("warp_table")
@EqualsAndHashCode(callSuper = true)
public class WarpData extends AbstractDataEntity {
    @Column("id")
    private Long id = new Date().getTime();
    @Column("name")
    private String name;
    @Column("location")
    private WorldLocation location;
}
