package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.interfaces.Registrable;

import java.util.ArrayList;
import java.util.List;

public class ServiceManager<T extends Registrable> {
    private T serviceClass;
    private Registrable service;

    public ServiceManager(Registrable service) {
        this.service = service;
    }
}
