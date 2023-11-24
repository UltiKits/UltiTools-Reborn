package com.ultikits.plugins.home.services;

import com.ultikits.plugins.home.PluginMain;
import com.ultikits.ultitools.abstracts.ServiceRegister;
import com.ultikits.ultitools.interfaces.Registrable;

public class HomeServiceRegister extends ServiceRegister<HomeService> {

    public HomeServiceRegister(Class<HomeService> api, Registrable service) {
        super(PluginMain.getPluginMain(), api, service);
    }
}
