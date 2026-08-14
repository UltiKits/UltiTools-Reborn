package com.ultikits.ultitools.abstracts;

/**
 * This abstract class represents a command executor.
 * It implements the TabExecutor interface from the Bukkit API.
 * <p>
 * 这个抽象类代表了一个命令执行器。
 * 它实现了Bukkit API中的TabExecutor接口。
 *
 * @see AbstractCommandExecutor
 * @deprecated Use {@link AbstractCommandExecutor} instead.
 */
@Deprecated(since = "6.2.1", forRemoval = true)
public abstract class AbstractCommendExecutor extends AbstractCommandExecutor {

    /**
     * Gets the instance of the command executor.
     * <p>
     * 获取命令执行器的实例。
     *
     * @return The instance of the command executor. <br> 命令执行器的实例。
     */
    @Override
    public AbstractCommendExecutor getInstance() {
        return this;
    }
}
