package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.interfaces.Registrable;

public class ServiceManager<T extends Registrable> {
    private T serviceClass;
    private Registrable service;

    public ServiceManager(Registrable service) {
        this.service = service;
    }
}
