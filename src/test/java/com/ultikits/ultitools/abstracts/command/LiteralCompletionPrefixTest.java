package com.ultikits.ultitools.abstracts.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.ultikits.ultitools.annotations.command.CmdParam;
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
        public void alphaEnable(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @ExceptionCatch
        @CmdMapping(format = "beta disable")
        public void betaDisable(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // fixture help: body intentionally empty
        }
    }

    /**
     * Three mappings on the {@code alpha} branch, two of which share the literal {@code enable}
     * at the completed position.
     */
    public static class DuplicateExecutor extends BaseCommandExecutor {

        @CmdMapping(format = "alpha enable")
        public void alphaEnable(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "alpha enable <target>")
        public void alphaEnableTarget(@CmdSender CommandSender sender, @CmdParam("target") String target) {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "alpha disable")
        public void alphaDisable(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // fixture help: body intentionally empty
        }
    }

    /**
     * One open literal, one permission-gated literal and one OP-only literal on the same branch.
     */
    public static class VisibilityExecutor extends BaseCommandExecutor {

        @CmdMapping(format = "alpha enable")
        public void alphaEnable(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "alpha secret", permission = "fixture.secret")
        public void alphaSecret(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @CmdMapping(format = "alpha admin", requireOp = true)
        public void alphaAdmin(@CmdSender CommandSender sender) {
            // fixture mapping: body intentionally empty
        }

        @Override
        protected void handleHelp(CommandSender sender) {
            // fixture help: body intentionally empty
        }
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

    @Test
    @DisplayName("a literal shared by several matching mappings is offered once")
    void literalCompletionHasNoDuplicates() {
        DuplicateExecutor executor = new DuplicateExecutor();

        List<String> completions = complete(executor, "alpha", "");

        assertThat(completions).containsExactlyInAnyOrder("enable", "disable");
        assertThat(completions).hasSize(2);
    }

    @Test
    @DisplayName("a permission-gated literal stays hidden from a player without the permission")
    void permissionHiddenLiteralStaysHidden() {
        VisibilityExecutor executor = new VisibilityExecutor();

        List<String> withoutPermission = complete(executor, "alpha", "");
        assertThat(withoutPermission).containsExactlyInAnyOrder("enable");
        assertThat(withoutPermission).hasSize(1);

        when(player.hasPermission("fixture.secret")).thenReturn(true);
        List<String> withPermission = complete(executor, "alpha", "");
        assertThat(withPermission).containsExactlyInAnyOrder("enable", "secret");
        assertThat(withPermission).hasSize(2);
    }

    @Test
    @DisplayName("an OP-only literal is offered only to an operator")
    void opOnlyLiteralStaysHidden() {
        VisibilityExecutor executor = new VisibilityExecutor();

        List<String> nonOp = complete(executor, "alpha", "");
        assertThat(nonOp).containsExactlyInAnyOrder("enable");
        assertThat(nonOp).hasSize(1);

        when(player.isOp()).thenReturn(true);
        List<String> op = complete(executor, "alpha", "");
        assertThat(op).containsExactlyInAnyOrder("enable", "admin");
        assertThat(op).hasSize(2);
    }
}
