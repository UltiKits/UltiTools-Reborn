package com.ultikits.testfixtures.beanname;

import com.ultikits.ultitools.annotations.PostConstruct;

/**
 * A POJO whose {@code @PostConstruct} invocation count proves a {@code @Bean} factory's product
 * was assembled exactly once, even when registered under several names (03-09, Task 1).
 */
public class PostConstructCounter {
    private int constructCount;

    @PostConstruct
    public void init() {
        constructCount++;
    }

    public int getConstructCount() {
        return constructCount;
    }
}
