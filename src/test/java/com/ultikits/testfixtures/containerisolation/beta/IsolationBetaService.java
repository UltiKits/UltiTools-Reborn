package com.ultikits.testfixtures.containerisolation.beta;

import com.ultikits.ultitools.annotations.Service;

/**
 * A bean that exists ONLY in Beta's container. Its bean name is what the isolation assertions
 * look for in the other module's container.
 */
@Service
public class IsolationBetaService {

    public String marker() {
        return "beta";
    }
}
