package com.ultikits.testfixtures.manualregisterlistener;

import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.Listener;

/**
 * A listener whose author deliberately withheld it from auto-registration via
 * {@code @EventListener(manualRegister = true)}. Neither registration entry point may register
 * this class.
 * <br>
 * 一个作者刻意通过 {@code @EventListener(manualRegister = true)} 不让其自动注册的监听器。
 * 任何一个注册入口点都不应注册这个类。
 */
@EventListener(manualRegister = true)
public class ManualRegisterListenerFixture implements Listener {
}
