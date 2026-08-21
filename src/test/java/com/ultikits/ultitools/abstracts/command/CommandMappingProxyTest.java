package com.ultikits.ultitools.abstracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.aop.AopEligibility;
import com.ultikits.ultitools.aop.ProxyFactory;
import com.ultikits.ultitools.annotations.ExceptionCatch;
import com.ultikits.ultitools.annotations.command.CmdMapping;

/**
 * Regression test for I4 (see issue #190): an AOP proxy only overrides the methods it intercepts,
 * so {@link BaseCommandExecutor#scanCommandMappings()} must walk the hierarchy via
 * {@link com.ultikits.ultitools.utils.ReflectionUtil#getAllMethods(Class)} instead of calling
 * {@code getDeclaredMethods()} on {@code this.getClass()} directly - otherwise every
 * {@code @CmdMapping} on a method the proxy does not override silently disappears from the
 * command's mapping table.
 */
@DisplayName("Command mapping survives AOP proxying")
class CommandMappingProxyTest {

    /**
     * Three {@code @CmdMapping} subcommands, one of which also carries an AOP annotation and is
     * therefore the only method the generated proxy overrides.
     */
    public static class ThreeMappingCommand extends BaseCommandExecutor {

        @CmdMapping(format = "a")
        public void a() { }

        @ExceptionCatch
        @CmdMapping(format = "b")
        public void b() { }

        @CmdMapping(format = "c")
        public void c() { }

        @Override
        protected void handleHelp(CommandSender sender) { }
    }

    /**
     * A parent and child that each declare an unrelated {@code @CmdMapping} method under the same
     * format string - not an override of one another, just a format collision across the hierarchy.
     */
    public static class ParentWithFormat extends BaseCommandExecutor {

        @CmdMapping(format = "x")
        public void parentMethod() { }

        @Override
        protected void handleHelp(CommandSender sender) { }
    }

    public static class ChildReusingFormat extends ParentWithFormat {

        @CmdMapping(format = "x")
        public void childMethod() { }
    }

    @Test
    @DisplayName("Should let the subclass's @CmdMapping win a format collision with its parent")
    void shouldPreferMostSpecificOverrideOnFormatCollision() throws Exception {
        ChildReusingFormat executor = new ChildReusingFormat();

        Method mapped = executor.getMappings().get("x");

        assertEquals("childMethod", mapped.getName(),
                "getAllMethods() lists the subclass first, and scanCommandMappings uses "
                        + "putIfAbsent so that first (most specific) entry wins - this pins that "
                        + "choice down instead of leaving it to iteration order");
    }

    @Test
    @DisplayName("Should keep every @CmdMapping after proxying, not only the intercepted one")
    void shouldKeepAllMappingsOnProxiedInstance() throws Exception {
        Set<Method> intercepted = AopEligibility.findAopAnnotatedMethods(ThreeMappingCommand.class);
        assertEquals(1, intercepted.size(),
                "precondition: exactly one method (b) requests interception");

        ProxyFactory factory = new ProxyFactory(Collections.emptyList());
        Class<? extends ThreeMappingCommand> proxyClass =
                factory.createProxyClass(ThreeMappingCommand.class, intercepted);
        BaseCommandExecutor proxyInstance = proxyClass.getDeclaredConstructor().newInstance();

        assertTrue(proxyInstance.getMappings().keySet().containsAll(
                        java.util.Arrays.asList("a", "b", "c")),
                "expected formats a, b, c but found " + proxyInstance.getMappings().keySet());
        assertEquals(3, proxyInstance.getMappings().size(),
                "the proxy's getDeclaredMethods() only carries the intercepted override (b); "
                        + "a and c must be recovered by walking the hierarchy");
    }
}
