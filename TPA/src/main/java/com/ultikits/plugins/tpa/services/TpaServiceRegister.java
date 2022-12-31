package com.ultikits.plugins.tpa.services;

import com.ultikits.ultitools.abstracts.ServiceRegister;
import com.ultikits.ultitools.interfaces.Registrable;

public class TpaServiceRegister extends ServiceRegister<TpaService> {
    public TpaServiceRegister(Class<TpaService> api, Registrable service) {
        super(api, service);
    }
}
