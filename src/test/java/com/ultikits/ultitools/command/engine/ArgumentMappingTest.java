package com.ultikits.ultitools.command.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.ultikits.ultitools.abstracts.command.parser.TypeParseException;
import com.ultikits.ultitools.abstracts.command.parser.TypeParser;

import io.papermc.paper.command.brigadier.CommandSourceStack;

class ArgumentMappingTest {
    static Stream<Class<?>> primitiveTypes() {
        return Stream.of(String.class, int.class, Integer.class, long.class, Long.class, double.class,
                Double.class, float.class, Float.class, boolean.class, Boolean.class, short.class,
                Short.class, byte.class, Byte.class);
    }
    static Stream<Class<?>> nativeTypes() {
        return Stream.of(Player.class, OfflinePlayer.class, World.class, GameMode.class, UUID.class,
                Enchantment.class, Material.class, Location.class);
    }
    @ParameterizedTest @MethodSource("primitiveTypes") void mapsEveryBasicType(Class<?> type) {
        assertThat(ArgumentMapping.of(type, false, new StringNativeArgumentTypes()).type()).isNotNull();
    }
    @ParameterizedTest @MethodSource("nativeTypes") void delegatesEachPlatformType(Class<?> type) {
        assertThat(ArgumentMapping.of(type, false, new StringNativeArgumentTypes()).type())
                .isInstanceOf(StringArgumentType.class);
    }
    @Test void shortRangeIsEnforcedByBrigadier() {
        var mapping = ArgumentMapping.of(short.class, false, new StringNativeArgumentTypes());
        assertThatThrownBy(() -> mapping.type().parse(new StringReader("32768")))
                .isInstanceOf(CommandSyntaxException.class);
        assertThat(mapping.type()).isInstanceOf(IntegerArgumentType.class);
    }
    @Test void readsUnicodeAndQuotedTokens() throws Exception {
        var argument = new TokenArgumentType();
        assertThat(argument.parse(new StringReader("家 rest"))).isEqualTo("家");
        assertThat(argument.parse(new StringReader("\"a b\""))).isEqualTo("a b");
        assertThat(argument.getNativeType()).isInstanceOf(StringArgumentType.class);
    }
    @Test void customParseFailureBecomesASyntaxExceptionWithoutLosingItsMessage() {
        var type = new TypeParserArgumentType<>(new FailingParser());
        assertThatThrownBy(() -> type.parse(new StringReader("bad")))
                .isInstanceOf(CommandSyntaxException.class).hasMessageContaining("custom failure detail");
    }
    @Test void greedyArrayReadsTypedValuesAndEmptyArray() throws Exception {
        var mapping = ArgumentMapping.of(short[].class, true, new StringNativeArgumentTypes());
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("array")
                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("items", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            assertThat((short[]) mapping.value(ctx, "items", false)).containsExactly((short) 1, (short) 2);
                            return 1;
                        })));
        assertThat(dispatcher.execute("array 1 2", mock(CommandSourceStack.class))).isEqualTo(1);
    }
    static class FailingParser implements TypeParser<Object> {
        @Override public Class<Object> getPrimaryType() { return Object.class; }
        @Override public List<Class<?>> getSupportedTypes() { return List.of(Object.class); }
        @Override public Object parse(String value) { throw new TypeParseException("custom failure detail"); }
    }
}
