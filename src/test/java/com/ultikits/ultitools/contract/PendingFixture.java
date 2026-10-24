package com.ultikits.ultitools.contract;

import org.junit.jupiter.api.Tag;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 待夹具：仅用于尚未导出的 P3 catalogue REST 手写样本；冻结的协议夹具直接使用。
 * 计划 22-26 在真实目录夹具可用后替换样本并删除本标记。
 */
@Tag("pending-fixture")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PendingFixture { }
