package com.ultikits.ultitools.interfaces;

/**
 * Cacheable data operation interface.
 *
 * @author wisdomme
 * @version 1.0.0
 */
public interface Cached {

    /**
     * Persist cache object.
     */
    void flush();

    /**
     * Compare local with cache and delete local persistent files.
     */
    void gc();
}
