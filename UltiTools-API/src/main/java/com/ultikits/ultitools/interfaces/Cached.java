package com.ultikits.ultitools.interfaces;

/**
 * 可缓存的数据操作接口
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface Cached {

    /**
     * 持久化缓存对象
     */
    void flush();

    /**
     * 对比本地与缓存，删除本地持久化文件
     */
    void gc();
}
