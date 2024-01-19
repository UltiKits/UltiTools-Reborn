package com.ultikits.plugins.basic.data;

import com.ultikits.ultitools.abstracts.AbstractDataEntity;
import com.ultikits.ultitools.annotations.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Table("baned_user")
@EqualsAndHashCode(callSuper = true)
public class BanedUserData extends AbstractDataEntity {
    private String name;
    private String reason;
    private String operator;
    private String time;
}
