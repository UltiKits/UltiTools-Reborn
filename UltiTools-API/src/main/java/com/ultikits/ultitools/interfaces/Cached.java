package com.ultikits.ultitools.interfaces;

/**
 * Cacheable data operation interface.
 * <p>
 * 可缓存的数据操作接口
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface Cached {

    /**
     * Persist cache object.
     * <p>
     * 持久化缓存对象
     */
    void flush();

    /**
     * Compare local with cache and delete local persistent files.
     * <p>
     * 对比本地与缓存，删除本地持久化文件
     */
    void gc();
}
