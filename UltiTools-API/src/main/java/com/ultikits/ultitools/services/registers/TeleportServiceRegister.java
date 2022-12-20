package com.ultikits.ultitools.services.registers;

import com.ultikits.ultitools.abstracts.ServiceRegister;
import com.ultikits.ultitools.interfaces.Registrable;
import com.ultikits.ultitools.services.TeleportService;

public class TeleportServiceRegister extends ServiceRegister<TeleportService> {
    public TeleportServiceRegister(Class<TeleportService> api, Registrable service) {
        super(api, service);
    }
}
