package com.ultikits.testfixtures.externalmessages;

import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Bean;
import com.ultikits.ultitools.annotations.Configuration;
import com.ultikits.ultitools.text.Messages;
import java.util.Locale;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessagesProbePlugin extends JavaPlugin {
    @Configuration
    public static class ProbeConfiguration {
        @Autowired
        private Messages messages;

        @Bean
        public ProbeBinding probeBinding() {
            return new ProbeBinding(messages, messages.isDefined(messages.key("greeting"), Locale.ENGLISH));
        }
    }

    public record ProbeBinding(Messages messages, boolean definedDuringScan) { }
}
