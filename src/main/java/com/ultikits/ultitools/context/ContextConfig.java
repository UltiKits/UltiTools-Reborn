package com.ultikits.ultitools.context;

import com.ultikits.ultitools.annotations.Configuration;
import org.jetbrains.annotations.ApiStatus;

/**
 * Marker configuration class for the framework's own context. It no longer declares a scan:
 * the scan declaration this class used to carry was removed in 6.3.0 because nothing ever
 * invoked {@link SimpleContainer#processConfigurationClass} against it in production - only
 * tests did (see {@code FinalContractValidator}'s "Known gaps" javadoc for the full story).
 * <br>
 * 框架自身上下文的标记配置类，不再声明扫描：本类曾经携带的扫描声明已在 6.3.0 移除，因为生产环境
 * 中从未有代码对它调用过 {@link SimpleContainer#processConfigurationClass}——只有测试会调用它。
 */
@Configuration
@ApiStatus.Internal
public class ContextConfig {
}
