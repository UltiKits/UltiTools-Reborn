package com.ultikits.testfixtures.registrationparity;

/**
 * A {@code @ContextEntry} target with no accessible no-arg constructor -- exercises the existing
 * WARNING-and-continue path both entry points must preserve after the {@code @ContextEntry}
 * handling block moves into the shared assembly method (WIRE-06).
 * <br>
 * 一个没有可访问无参构造器的 {@code @ContextEntry} 目标类——用于验证
 * {@code @ContextEntry} 处理块搬进共享装配方法后，两个入口点仍然保留既有的"记录 WARNING
 * 并继续"路径（WIRE-06）。
 */
public class ParityUnconstructableContextBean {

    @SuppressWarnings("unused")
    public ParityUnconstructableContextBean(String requiredArg) {
    }
}
