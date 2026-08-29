package com.ultikits.testfixtures.containerisolation.alpha;

import com.ultikits.ultitools.annotations.Service;

/**
 * A bean that exists ONLY in Alpha's container. Its bean name is what the isolation assertions
 * look for in the other module's container.
 */
@Service
public class IsolationAlphaService {

    public String marker() {
        return "alpha";
    }
}
