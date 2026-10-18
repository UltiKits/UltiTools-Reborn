package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.ProxyFactory;

/**
 * Literal completion of a later argument follows the branch the player already typed (case 61,
 * CORR-08).
 * <p>
 * Before the fix, completing {@code /cmd alpha <tab>} offered the literal at that position from
 * every visible mapping -- including {@code beta disable}, which cannot run after {@code alpha}
 * -- and repeated that whole list once per matching mapping. Every test enters through the
 * executor's own {@code onTabComplete}, the path Bukkit calls.
 * <p>
 * A Mockito {@link Player} is enough: the fixtures declare no class-level {@code @CmdTarget},
 * and literal candidates never reach {@code TabCompletionManager}. Unstubbed, {@code
 * hasPermission} and {@code isOp} return {@code false}, which keeps permission-free mappings
 * visible.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Literal completion keeps the typed prefix (case 61)")
class LiteralCompletionPrefixTest {

    private Player player;
    private Command command;

    /**
     * Two branches whose second tokens differ; {@code beta disable} carries an AOP annotation so
     * a generated proxy overrides exactly that mapping.
     */
    public static class BranchExecutor extends BaseCommandExecutor {

        @CmdMapping(format = "alpha enable")
        public void alphaEnable(@CmdSender CommandSender sender) { }

        @ExceptionCatch
        @CmdMapping(format = "beta disable")
        public void betaDisable(@CmdSender CommandSender sender) { }

        @Override
        protected void handleHelp(CommandSender sender) { }
    }

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        command = mock(Command.class);
    }

    private List<String> complete(BaseCommandExecutor executor, String... args) {
        return executor.onTabComplete(player, command, "fixture", args);
    }

    @Test
    @DisplayName("a typed branch is offered only that branch's next literal")
    void literalCompletionKeepsOnlyTheMatchedBranch() {
        BranchExecutor executor = new BranchExecutor();

        assertThat(complete(executor, "alpha", "")).containsExactly("enable");
        assertThat(complete(executor, "beta", "")).containsExactly("disable");
    }

    @Test
    @DisplayName("an AOP-proxied executor completes exactly like the ordinary one")
    void proxiedShapeBehavesTheSame() throws Exception {
        Set<Method> intercepted = AopEligibility.findAopAnnotatedMethods(BranchExecutor.class);
        Class<? extends BranchExecutor> proxyClass =
                new ProxyFactory(Collections.emptyList()).createProxyClass(BranchExecutor.class, intercepted);
        BaseCommandExecutor proxy = proxyClass.getDeclaredConstructor().newInstance();

        Method betaDisable = proxy.getMappings().get("beta disable");
        assertThat(betaDisable).as("precondition: the proxy carries the beta disable mapping").isNotNull();
        assertThat(ProxyFactory.isProxyClass(betaDisable.getDeclaringClass()))
                .as("precondition: beta disable is the proxy's own override, not the author's method")
                .isTrue();

        assertThat(complete(proxy, "alpha", "")).containsExactly("enable");
        assertThat(complete(proxy, "beta", "")).containsExactly("disable");
    }
}
