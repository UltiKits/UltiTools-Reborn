package com.ultikits.ultitools.context;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(basePackages = "com.ultikits.ultitools", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.ultikits\\.ultitools\\.commands\\..*")
})
public class ContextConfig {
}
