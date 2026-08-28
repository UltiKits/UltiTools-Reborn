package com.ultikits.ultitools.exceptions;

/**
 * Signals that a transaction's outer {@code commit()} found the transaction already marked
 * rollback-only by a nested scope, and performed a real rollback instead of committing.
 * <p>
 * This is the analogue of the signal a Spring-family container raises
 * ({@code org.springframework.transaction.UnexpectedRollbackException}) when an outer commit
 * discovers an inner scope already decided the whole transaction cannot succeed. Before D-08, the
 * inner scope's decision was discarded silently - the outer {@code commit()} logged a WARNING and
 * returned normally, leaving the caller believing its work was persisted when it was not. Throwing
 * this instead makes that outcome observable.
 * <p>
 * 标记外层 {@code commit()} 发现事务已被内层作用域标记为「只能回滚」，因而执行了真正的回滚
 * 而非提交。这是 Spring 系容器中同类信号
 * （{@code org.springframework.transaction.UnexpectedRollbackException}）在本框架里的对应物：
 * 外层提交发现内层作用域已经判定整个事务无法成功。D-08 之前，内层的这一判定会被静默丢弃——
 * 外层 {@code commit()} 只记一条 WARNING 便正常返回，调用方会误以为自己的工作已经持久化，
 * 实际并未发生。改为抛出该异常后，这一结果不再是静默的。
 *
 * @author wisdomme
 * @since 6.3.0
 */
public class UnexpectedRollbackException extends UltiToolsException {

    /**
     * Creates a new unexpected-rollback exception with a generic error code.
     *
     * @param message the error message
     */
    public UnexpectedRollbackException(String message) {
        super(ErrorCode.TRANSACTION_ROLLBACK_ONLY, message);
    }

    /**
     * Creates a new unexpected-rollback exception with a specific error code.
     *
     * @param errorCode the error code
     * @param message   the error message
     */
    public UnexpectedRollbackException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Creates a new unexpected-rollback exception with message and cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public UnexpectedRollbackException(String message, Throwable cause) {
        super(ErrorCode.TRANSACTION_ROLLBACK_ONLY, message, cause);
    }

    /**
     * Creates a new unexpected-rollback exception with error code, message, and cause.
     *
     * @param errorCode the error code
     * @param message   the error message
     * @param cause     the underlying cause
     */
    public UnexpectedRollbackException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Creates an exception naming the nested scope that marked the transaction rollback-only.
     *
     * @param origin a description of the nested scope that set the marker (e.g. a method name or
     *               transaction depth) - included verbatim in the message
     * @return a new UnexpectedRollbackException
     */
    public static UnexpectedRollbackException markedBy(String origin) {
        return new UnexpectedRollbackException(ErrorCode.TRANSACTION_ROLLBACK_ONLY,
                "Transaction was marked rollback-only by " + origin
                        + "; the outer commit() performed a real rollback instead of committing.");
    }
}
