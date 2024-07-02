package com.ultikits.ultitools.interfaces.impl.logger;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

public class BukkitLogFactory extends LogFactory {

	public BukkitLogFactory() {
		super("Bukkit Console Logging");
	}

	@Override
	public Log createLog(String name) {
		return new BukkitLog(name);
	}

	@Override
	public Log createLog(Class<?> clazz) {
		return new BukkitLog(clazz);
	}

}
