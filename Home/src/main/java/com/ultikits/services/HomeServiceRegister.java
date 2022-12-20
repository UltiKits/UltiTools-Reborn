package com.ultikits.services;

import com.ultikits.ultitools.abstracts.ServiceRegister;
import com.ultikits.ultitools.interfaces.Registrable;

public class HomeServiceRegister extends ServiceRegister<HomeService> {

    public HomeServiceRegister(Class<HomeService> api, Registrable service) {
        super(api, service);
    }
}
