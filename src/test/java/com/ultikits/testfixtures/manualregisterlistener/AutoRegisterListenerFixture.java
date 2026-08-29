package com.ultikits.testfixtures.manualregisterlistener;

import com.ultikits.ultitools.annotations.EventListener;

import org.bukkit.event.Listener;

/**
 * A listener that auto-registers -- the control proving the manual-register sibling's absence
 * is due to the {@code manualRegister = true} gate, not an unrelated scan/registration failure.
 * <br>
 * 一个会自动注册的监听器——作为对照，证明另一个手动注册的监听器缺席是因为
 * {@code manualRegister = true} 门控生效，而不是扫描或注册本身出了无关的问题。
 */
@EventListener
public class AutoRegisterListenerFixture implements Listener {
}
