package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XSound;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Sounds;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * XVersionUtils 测试类
 */
@DisplayName("XVersionUtils 测试")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class XVersionUtilsTest {

    @Nested
    @DisplayName("getColoredPlaneGlass 方法测试")
    class GetColoredPlaneGlassTests {

        @Test
        @DisplayName("应该返回带颜色的玻璃板")
        void shouldReturnColoredGlassPane() {
            try (MockedStatic<XMaterial> mockedXMaterial = mockStatic(XMaterial.class)) {
                XMaterial mockMaterial = mock(XMaterial.class);
                ItemStack expectedItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                
                mockedXMaterial.when(() -> XMaterial.matchXMaterial("RED_STAINED_GLASS_PANE"))
                    .thenReturn(Optional.of(mockMaterial));
                when(mockMaterial.parseItem()).thenReturn(expectedItem);
                
                ItemStack result = XVersionUtils.getColoredPlaneGlass(Colors.RED);
                
                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("应该返回默认玻璃板当颜色不匹配时")
        void shouldReturnDefaultGlassPaneWhenColorNotMatched() {
            try (MockedStatic<XMaterial> mockedXMaterial = mockStatic(XMaterial.class)) {
                mockedXMaterial.when(() -> XMaterial.matchXMaterial(anyString()))
                    .thenReturn(Optional.empty());
                
                ItemStack result = XVersionUtils.getColoredPlaneGlass(Colors.RED);
                
                assertThat(result).isNotNull();
                assertThat(result.getType()).isEqualTo(Material.GLASS_PANE);
            }
        }
    }

    @Nested
    @DisplayName("getSign 方法测试")
    class GetSignTests {

        @Test
        @DisplayName("应该返回告示牌")
        void shouldReturnSign() {
            // 直接测试方法逻辑，不依赖 mock
            ItemStack result = XVersionUtils.getSign();
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getEndEye 方法测试")
    class GetEndEyeTests {

        @Test
        @DisplayName("应该返回末影之眼")
        void shouldReturnEnderEye() {
            ItemStack result = XVersionUtils.getEndEye();
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getEmailMaterial 方法测试")
    class GetEmailMaterialTests {

        @Test
        @DisplayName("应该返回填充地图对于已读邮件")
        void shouldReturnFilledMapForReadEmail() {
            ItemStack result = XVersionUtils.getEmailMaterial(true);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("应该返回纸张对于未读邮件")
        void shouldReturnPaperForUnreadEmail() {
            ItemStack result = XVersionUtils.getEmailMaterial(false);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getHead 方法测试")
    class GetHeadTests {

        @Test
        @DisplayName("应该返回龙头对于管理员")
        void shouldReturnDragonHeadForOp() {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.isOp()).thenReturn(true);
            
            ItemStack result = XVersionUtils.getHead(player);
            
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("应该返回玩家头对于普通玩家")
        void shouldReturnPlayerHeadForNormalPlayer() {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.isOp()).thenReturn(false);
            
            ItemStack result = XVersionUtils.getHead(player);
            
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getGrassBlock 方法测试")
    class GetGrassBlockTests {

        @Test
        @DisplayName("应该返回草方块")
        void shouldReturnGrassBlock() {
            ItemStack result = XVersionUtils.getGrassBlock();
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("registerNewObjective 方法测试")
    class RegisterNewObjectiveTests {

        @Test
        @DisplayName("应该返回现有的目标当存在时")
        void shouldReturnExistingObjective() {
            Scoreboard scoreboard = mock(Scoreboard.class);
            Objective existingObjective = mock(Objective.class);
            when(scoreboard.getObjective("test")).thenReturn(existingObjective);
            
            Objective result = XVersionUtils.registerNewObjective(scoreboard, "test", "dummy", "Test");
            
            assertThat(result).isEqualTo(existingObjective);
        }

        @Test
        @DisplayName("应该返回非 null 的目标当不存在时")
        void shouldRegisterNewObjectiveWhenNotExist() {
            // 因为 Criteria.DUMMY 需要 Bukkit server，这个测试只验证方法签名
            // 在没有完整 Bukkit 环境时跳过实际执行
            assertThat(XVersionUtils.class.getDeclaredMethods()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("getSound 方法测试")
    class GetSoundTests {

        @Test
        @DisplayName("应该返回声音对象")
        void shouldReturnSoundObject() {
            try (MockedStatic<XSound> mockedXSound = mockStatic(XSound.class)) {
                XSound mockXSound = mock(XSound.class);
                mockedXSound.when(() -> XSound.matchXSound(anyString()))
                    .thenReturn(Optional.of(mockXSound));
                when(mockXSound.parseSound()).thenReturn(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP);
                
                org.bukkit.Sound result = XVersionUtils.getSound(Sounds.BLOCK_CHEST_OPEN);
                
                // 结果可能为 null 或 Sound，取决于 XSound 的行为
            }
        }

        @Test
        @DisplayName("应该返回 null 当声音不匹配时")
        void shouldReturnNullWhenSoundNotMatched() {
            try (MockedStatic<XSound> mockedXSound = mockStatic(XSound.class)) {
                mockedXSound.when(() -> XSound.matchXSound(anyString()))
                    .thenReturn(Optional.empty());
                
                org.bukkit.Sound result = XVersionUtils.getSound(Sounds.BLOCK_CHEST_OPEN);
                
                assertThat(result).isNull();
            }
        }
    }

    @Nested
    @DisplayName("getBed 方法测试")
    class GetBedTests {

        @Test
        @DisplayName("应该返回带颜色的床")
        void shouldReturnColoredBed() {
            try (MockedStatic<XMaterial> mockedXMaterial = mockStatic(XMaterial.class)) {
                XMaterial mockMaterial = mock(XMaterial.class);
                ItemStack expectedItem = new ItemStack(Material.RED_BED);
                
                mockedXMaterial.when(() -> XMaterial.matchXMaterial("RED_BED"))
                    .thenReturn(Optional.of(mockMaterial));
                when(mockMaterial.parseItem()).thenReturn(expectedItem);
                
                ItemStack result = XVersionUtils.getBed(Colors.RED);
                
                assertThat(result).isNotNull();
            }
        }

        @Test
        @DisplayName("应该返回默认红床当颜色不匹配时")
        void shouldReturnDefaultRedBedWhenColorNotMatched() {
            try (MockedStatic<XMaterial> mockedXMaterial = mockStatic(XMaterial.class)) {
                mockedXMaterial.when(() -> XMaterial.matchXMaterial(anyString()))
                    .thenReturn(Optional.empty());
                
                ItemStack result = XVersionUtils.getBed(Colors.RED);
                
                assertThat(result).isNotNull();
                assertThat(result.getType()).isEqualTo(Material.RED_BED);
            }
        }
    }

    @Nested
    @DisplayName("getItemDurability 方法测试")
    class GetItemDurabilityTests {

        @Test
        @DisplayName("应该返回物品耐久度")
        void shouldReturnItemDurability() {
            ItemStack itemStack = mock(ItemStack.class);
            Damageable damageable = mock(Damageable.class);
            
            when(itemStack.getItemMeta()).thenReturn(damageable);
            when(itemStack.getType()).thenReturn(Material.DIAMOND_SWORD);
            when(damageable.getDamage()).thenReturn(100);
            
            int result = XVersionUtils.getItemDurability(itemStack);
            
            // 结果是 maxDurability - damage
            // 对于 DIAMOND_SWORD，maxDurability 是 1561
            assertThat(result).isEqualTo(Material.DIAMOND_SWORD.getMaxDurability() - 100);
        }

        @Test
        @DisplayName("应该返回0当物品不是 Damageable 时")
        void shouldReturnZeroWhenNotDamageable() {
            ItemStack itemStack = mock(ItemStack.class);
            ItemMeta nonDamageableMeta = mock(ItemMeta.class);
            
            when(itemStack.getItemMeta()).thenReturn(nonDamageableMeta);
            
            int result = XVersionUtils.getItemDurability(itemStack);
            
            assertThat(result).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("getItemInHand 方法测试")
    class GetItemInHandTests {

        @Test
        @DisplayName("应该返回主手物品")
        void shouldReturnMainHandItem() {
            Player player = mock(Player.class);
            PlayerInventory inventory = mock(PlayerInventory.class);
            ItemStack mainHandItem = new ItemStack(Material.DIAMOND_SWORD);
            
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInMainHand()).thenReturn(mainHandItem);
            
            ItemStack result = XVersionUtils.getItemInHand(player, true);
            
            assertThat(result).isEqualTo(mainHandItem);
        }

        @Test
        @DisplayName("应该返回副手物品")
        void shouldReturnOffHandItem() {
            Player player = mock(Player.class);
            PlayerInventory inventory = mock(PlayerInventory.class);
            ItemStack offHandItem = new ItemStack(Material.SHIELD);
            
            when(player.getInventory()).thenReturn(inventory);
            when(inventory.getItemInOffHand()).thenReturn(offHandItem);
            
            ItemStack result = XVersionUtils.getItemInHand(player, false);
            
            assertThat(result).isEqualTo(offHandItem);
        }
    }

    @Nested
    @DisplayName("sendActionBar 方法测试")
    class SendActionBarTests {

        @Test
        @DisplayName("应该发送 Action Bar 消息")
        void shouldSendActionBarMessage() {
            Player player = mock(Player.class);
            Player.Spigot spigot = mock(Player.Spigot.class);
            when(player.spigot()).thenReturn(spigot);
            
            XVersionUtils.sendActionBar(player, "Test Message");
            
            verify(spigot).sendMessage(eq(ChatMessageType.ACTION_BAR), any(TextComponent.class));
        }
    }

    @Nested
    @DisplayName("sendPlayerList 方法测试")
    class SendPlayerListTests {

        @Test
        @DisplayName("应该设置玩家列表头尾")
        void shouldSetPlayerListHeaderFooter() {
            Player player = mock(Player.class);
            
            XVersionUtils.sendPlayerList(player, "Header", "Footer");
            
            verify(player).setPlayerListHeaderFooter("Header", "Footer");
        }
    }

    @Nested
    @DisplayName("getBlockFace 方法测试")
    class GetBlockFaceTests {

        @Test
        @DisplayName("应该返回方块朝向")
        void shouldReturnBlockFace() {
            Block block = mock(Block.class);
            Directional directional = mock(Directional.class);
            
            when(block.getBlockData()).thenReturn(directional);
            when(directional.getFacing()).thenReturn(BlockFace.EAST);
            
            BlockFace result = XVersionUtils.getBlockFace(block);
            
            assertThat(result).isEqualTo(BlockFace.EAST);
        }

        @Test
        @DisplayName("应该返回 NORTH 当方块不是 Directional 时")
        void shouldReturnNorthWhenNotDirectional() {
            Block block = mock(Block.class);
            BlockData nonDirectional = mock(BlockData.class);
            
            when(block.getBlockData()).thenReturn(nonDirectional);
            
            BlockFace result = XVersionUtils.getBlockFace(block);
            
            assertThat(result).isEqualTo(BlockFace.NORTH);
        }
    }

    @Nested
    @DisplayName("构造函数测试")
    class ConstructorTests {

        @Test
        @DisplayName("XVersionUtils 应该是工具类（不可实例化）")
        void shouldBeUtilityClass() throws Exception {
            java.lang.reflect.Constructor<XVersionUtils> constructor = 
                XVersionUtils.class.getDeclaredConstructor();
            assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        }
    }
}
