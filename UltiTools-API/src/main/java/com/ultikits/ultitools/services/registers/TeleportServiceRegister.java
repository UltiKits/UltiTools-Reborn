package com.ultikits.ultitools.services.registers;

import com.ultikits.ultitools.abstracts.ServiceRegister;
import com.ultikits.ultitools.interfaces.Registrable;
import com.ultikits.ultitools.services.TeleportService;

/**
 * 传送服务注册器
 *
 * @author wisdomme
 * @version 1.0.0
 */
public class TeleportServiceRegister extends ServiceRegister<TeleportService> {
    public TeleportServiceRegister(Class<TeleportService> api, Registrable service) {
        super(api, service);
    }
}
