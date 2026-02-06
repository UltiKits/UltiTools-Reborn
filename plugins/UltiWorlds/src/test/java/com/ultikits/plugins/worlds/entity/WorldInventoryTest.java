package com.ultikits.plugins.worlds.entity;

import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for WorldInventory entity.
 *
 * @author wisdomme
 * @version 2.0.0
 */
@DisplayName("WorldInventory Entity Tests")
class WorldInventoryTest {

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("create should initialize with default values")
        void createDefaultValues() {
            UUID playerUuid = UUID.randomUUID();
            String worldGroup = "group_0";

            WorldInventory inventory = WorldInventory.create(playerUuid, worldGroup);

            assertThat(inventory.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(inventory.getWorldGroup()).isEqualTo(worldGroup);
            assertThat(inventory.getExperienceLevel()).isEqualTo(0);
            assertThat(inventory.getExperiencePoints()).isEqualTo(0f);
            assertThat(inventory.getHealth()).isEqualTo(20.0);
            assertThat(inventory.getMaxHealth()).isEqualTo(20.0);
            assertThat(inventory.getFoodLevel()).isEqualTo(20);
            assertThat(inventory.getSaturation()).isEqualTo(5.0f);
            assertThat(inventory.getLastUpdated()).isGreaterThan(0);
        }

        @Test
        @DisplayName("create should set timestamp")
        void createTimestamp() {
            UUID playerUuid = UUID.randomUUID();
            long before = System.currentTimeMillis();

            WorldInventory inventory = WorldInventory.create(playerUuid, "test_group");

            long after = System.currentTimeMillis();
            assertThat(inventory.getLastUpdated()).isBetween(before, after);
        }

        @Test
        @DisplayName("create should leave nullable fields as null")
        void createNullFields() {
            UUID playerUuid = UUID.randomUUID();

            WorldInventory inventory = WorldInventory.create(playerUuid, "group");

            assertThat(inventory.getInventoryData()).isNull();
            assertThat(inventory.getArmorData()).isNull();
            assertThat(inventory.getOffHandData()).isNull();
            assertThat(inventory.getEnderChestData()).isNull();
            assertThat(inventory.getEffectsData()).isNull();
        }
    }

    @Nested
    @DisplayName("Data Fields")
    class DataFields {

        @Test
        @DisplayName("Should store player UUID")
        void playerUuid() {
            WorldInventory inventory = new WorldInventory();
            UUID uuid = UUID.randomUUID();

            inventory.setPlayerUuid(uuid.toString());

            assertThat(inventory.getPlayerUuid()).isEqualTo(uuid.toString());
        }

        @Test
        @DisplayName("Should store world group")
        void worldGroup() {
            WorldInventory inventory = new WorldInventory();

            inventory.setWorldGroup("pvp_worlds");

            assertThat(inventory.getWorldGroup()).isEqualTo("pvp_worlds");
        }

        @Test
        @DisplayName("Should store inventory data")
        void inventoryData() {
            WorldInventory inventory = new WorldInventory();
            String data = "serialized_inventory_data";

            inventory.setInventoryData(data);

            assertThat(inventory.getInventoryData()).isEqualTo(data);
        }

        @Test
        @DisplayName("Should store armor data")
        void armorData() {
            WorldInventory inventory = new WorldInventory();
            String data = "serialized_armor_data";

            inventory.setArmorData(data);

            assertThat(inventory.getArmorData()).isEqualTo(data);
        }

        @Test
        @DisplayName("Should store off-hand data")
        void offHandData() {
            WorldInventory inventory = new WorldInventory();
            String data = "serialized_offhand_data";

            inventory.setOffHandData(data);

            assertThat(inventory.getOffHandData()).isEqualTo(data);
        }

        @Test
        @DisplayName("Should store ender chest data")
        void enderChestData() {
            WorldInventory inventory = new WorldInventory();
            String data = "serialized_enderchest_data";

            inventory.setEnderChestData(data);

            assertThat(inventory.getEnderChestData()).isEqualTo(data);
        }

        @Test
        @DisplayName("Should store effects data")
        void effectsData() {
            WorldInventory inventory = new WorldInventory();
            String data = "SPEED,600,1,false,true;REGENERATION,200,0,true,true";

            inventory.setEffectsData(data);

            assertThat(inventory.getEffectsData()).isEqualTo(data);
        }

        @Test
        @DisplayName("Should store last updated timestamp")
        void lastUpdated() {
            WorldInventory inventory = new WorldInventory();
            long ts = 1700000000000L;

            inventory.setLastUpdated(ts);

            assertThat(inventory.getLastUpdated()).isEqualTo(ts);
        }
    }

    @Nested
    @DisplayName("Player Stats")
    class PlayerStats {

        @Test
        @DisplayName("Should store experience level")
        void experienceLevel() {
            WorldInventory inventory = new WorldInventory();

            inventory.setExperienceLevel(50);

            assertThat(inventory.getExperienceLevel()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should store experience points")
        void experiencePoints() {
            WorldInventory inventory = new WorldInventory();

            inventory.setExperiencePoints(0.75f);

            assertThat(inventory.getExperiencePoints()).isEqualTo(0.75f);
        }

        @Test
        @DisplayName("Should store health")
        void health() {
            WorldInventory inventory = new WorldInventory();

            inventory.setHealth(15.5);

            assertThat(inventory.getHealth()).isEqualTo(15.5);
        }

        @Test
        @DisplayName("Should store max health")
        void maxHealth() {
            WorldInventory inventory = new WorldInventory();

            inventory.setMaxHealth(30.0);

            assertThat(inventory.getMaxHealth()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("Should store food level")
        void foodLevel() {
            WorldInventory inventory = new WorldInventory();

            inventory.setFoodLevel(15);

            assertThat(inventory.getFoodLevel()).isEqualTo(15);
        }

        @Test
        @DisplayName("Should store saturation")
        void saturation() {
            WorldInventory inventory = new WorldInventory();

            inventory.setSaturation(3.5f);

            assertThat(inventory.getSaturation()).isEqualTo(3.5f);
        }

        @Test
        @DisplayName("Should handle zero health")
        void zeroHealth() {
            WorldInventory inventory = new WorldInventory();

            inventory.setHealth(0.0);

            assertThat(inventory.getHealth()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should handle zero food level")
        void zeroFoodLevel() {
            WorldInventory inventory = new WorldInventory();

            inventory.setFoodLevel(0);

            assertThat(inventory.getFoodLevel()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle zero experience")
        void zeroExperience() {
            WorldInventory inventory = new WorldInventory();

            inventory.setExperienceLevel(0);
            inventory.setExperiencePoints(0.0f);

            assertThat(inventory.getExperienceLevel()).isEqualTo(0);
            assertThat(inventory.getExperiencePoints()).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("Lifecycle Hooks")
    class LifecycleHooks {

        @Test
        @DisplayName("onCreate should set lastUpdated timestamp")
        void onCreate() {
            WorldInventory inventory = new WorldInventory();

            long before = System.currentTimeMillis();
            inventory.onCreate();
            long after = System.currentTimeMillis();

            assertThat(inventory.getLastUpdated()).isBetween(before, after);
        }

        @Test
        @DisplayName("onUpdate should update lastUpdated timestamp")
        void onUpdate() {
            WorldInventory inventory = new WorldInventory();
            inventory.onCreate();

            long firstTimestamp = inventory.getLastUpdated();

            // Small delay to ensure timestamp difference
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                // Ignore
            }

            inventory.onUpdate();

            assertThat(inventory.getLastUpdated()).isGreaterThan(firstTimestamp);
        }

        @Test
        @DisplayName("onUpdate should work without prior onCreate")
        void onUpdateWithoutCreate() {
            WorldInventory inventory = new WorldInventory();
            assertThat(inventory.getLastUpdated()).isEqualTo(0L);

            inventory.onUpdate();

            assertThat(inventory.getLastUpdated()).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("Two inventories with same data should be equal")
        void equalObjects() {
            UUID uuid = UUID.randomUUID();
            WorldInventory inv1 = WorldInventory.create(uuid, "group_0");
            WorldInventory inv2 = WorldInventory.create(uuid, "group_0");
            // Set same timestamp to ensure equality
            long ts = System.currentTimeMillis();
            inv1.setLastUpdated(ts);
            inv2.setLastUpdated(ts);

            assertThat(inv1).isEqualTo(inv2);
            assertThat(inv1.hashCode()).isEqualTo(inv2.hashCode());
        }

        @Test
        @DisplayName("Two inventories with different data should not be equal")
        void notEqualObjects() {
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();
            WorldInventory inv1 = WorldInventory.create(uuid1, "group_0");
            WorldInventory inv2 = WorldInventory.create(uuid2, "group_1");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventory should not equal null")
        void notEqualNull() {
            WorldInventory inventory = new WorldInventory();

            assertThat(inventory).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Inventory should not equal different type")
        void notEqualDifferentType() {
            WorldInventory inventory = new WorldInventory();

            assertThat(inventory.equals("string")).isFalse();
        }

        @Test
        @DisplayName("Inventory should equal itself")
        void equalSelf() {
            WorldInventory inventory = WorldInventory.create(UUID.randomUUID(), "group");

            assertThat(inventory).isEqualTo(inventory);
        }

        @Test
        @DisplayName("Inventories differing in playerUuid should not be equal")
        void notEqualDifferentPlayerUuid() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setPlayerUuid("uuid1");
            inv1.setWorldGroup("group");

            WorldInventory inv2 = new WorldInventory();
            inv2.setPlayerUuid("uuid2");
            inv2.setWorldGroup("group");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in worldGroup should not be equal")
        void notEqualDifferentWorldGroup() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setPlayerUuid("uuid1");
            inv1.setWorldGroup("group1");

            WorldInventory inv2 = new WorldInventory();
            inv2.setPlayerUuid("uuid1");
            inv2.setWorldGroup("group2");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in inventoryData should not be equal")
        void notEqualDifferentInventoryData() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setInventoryData("data1");

            WorldInventory inv2 = new WorldInventory();
            inv2.setInventoryData("data2");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in armorData should not be equal")
        void notEqualDifferentArmorData() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setArmorData("data1");

            WorldInventory inv2 = new WorldInventory();
            inv2.setArmorData("data2");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in offHandData should not be equal")
        void notEqualDifferentOffHandData() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setOffHandData("data1");

            WorldInventory inv2 = new WorldInventory();
            inv2.setOffHandData("data2");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in enderChestData should not be equal")
        void notEqualDifferentEnderChestData() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setEnderChestData("data1");

            WorldInventory inv2 = new WorldInventory();
            inv2.setEnderChestData("data2");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in experienceLevel should not be equal")
        void notEqualDifferentExpLevel() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setExperienceLevel(10);

            WorldInventory inv2 = new WorldInventory();
            inv2.setExperienceLevel(20);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in experiencePoints should not be equal")
        void notEqualDifferentExpPoints() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setExperiencePoints(0.5f);

            WorldInventory inv2 = new WorldInventory();
            inv2.setExperiencePoints(0.9f);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in health should not be equal")
        void notEqualDifferentHealth() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setHealth(10.0);

            WorldInventory inv2 = new WorldInventory();
            inv2.setHealth(15.0);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in maxHealth should not be equal")
        void notEqualDifferentMaxHealth() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setMaxHealth(20.0);

            WorldInventory inv2 = new WorldInventory();
            inv2.setMaxHealth(40.0);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in foodLevel should not be equal")
        void notEqualDifferentFoodLevel() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setFoodLevel(20);

            WorldInventory inv2 = new WorldInventory();
            inv2.setFoodLevel(10);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in saturation should not be equal")
        void notEqualDifferentSaturation() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setSaturation(5.0f);

            WorldInventory inv2 = new WorldInventory();
            inv2.setSaturation(2.0f);

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in effectsData should not be equal")
        void notEqualDifferentEffectsData() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setEffectsData("SPEED,100,0,false,true");

            WorldInventory inv2 = new WorldInventory();
            inv2.setEffectsData("REGENERATION,200,1,true,false");

            assertThat(inv1).isNotEqualTo(inv2);
        }

        @Test
        @DisplayName("Inventories differing in lastUpdated should not be equal")
        void notEqualDifferentLastUpdated() {
            WorldInventory inv1 = new WorldInventory();
            inv1.setLastUpdated(1000L);

            WorldInventory inv2 = new WorldInventory();
            inv2.setLastUpdated(2000L);

            assertThat(inv1).isNotEqualTo(inv2);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain class name and fields")
        void toStringContainsFields() {
            UUID uuid = UUID.randomUUID();
            WorldInventory inventory = WorldInventory.create(uuid, "group_0");

            String result = inventory.toString();

            assertThat(result).contains("WorldInventory");
            assertThat(result).contains(uuid.toString());
            assertThat(result).contains("group_0");
        }

        @Test
        @DisplayName("toString should work on empty inventory")
        void toStringEmpty() {
            WorldInventory inventory = new WorldInventory();

            String result = inventory.toString();

            assertThat(result).isNotNull();
            assertThat(result).contains("WorldInventory");
        }
    }

    @Nested
    @DisplayName("BaseDataEntity Methods")
    class BaseDataEntityMethods {

        @Test
        @DisplayName("isNew should return true when id is null")
        void isNewTrue() {
            WorldInventory inventory = new WorldInventory();

            assertThat(inventory.isNew()).isTrue();
        }

        @Test
        @DisplayName("isNew should return false when id is set")
        void isNewFalse() {
            WorldInventory inventory = new WorldInventory();
            inventory.setId(1);

            assertThat(inventory.isNew()).isFalse();
        }

        @Test
        @DisplayName("getId and setId should work correctly")
        void getSetId() {
            WorldInventory inventory = new WorldInventory();

            inventory.setId(42);

            assertThat(inventory.getId()).isEqualTo(42);
        }

        @Test
        @DisplayName("validate should return true by default")
        void validateDefault() {
            WorldInventory inventory = new WorldInventory();

            assertThat(inventory.validate()).isTrue();
        }

        @Test
        @DisplayName("getValidationErrors should return empty list by default")
        void validationErrorsDefault() {
            WorldInventory inventory = new WorldInventory();

            assertThat(inventory.getValidationErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Complete Inventory Scenario")
    class CompleteScenario {

        @Test
        @DisplayName("Should handle complete inventory save scenario")
        void completeInventorySave() {
            UUID playerUuid = UUID.randomUUID();
            WorldInventory inventory = WorldInventory.create(playerUuid, "survival_worlds");

            // Simulate saving player data
            inventory.setInventoryData("inv_data_base64");
            inventory.setArmorData("armor_data_base64");
            inventory.setOffHandData("offhand_data_base64");
            inventory.setEnderChestData("enderchest_data_base64");
            inventory.setExperienceLevel(35);
            inventory.setExperiencePoints(0.65f);
            inventory.setHealth(18.5);
            inventory.setMaxHealth(20.0);
            inventory.setFoodLevel(17);
            inventory.setSaturation(4.2f);
            inventory.setEffectsData("SPEED,300,0,false,true");

            // Verify all data
            assertThat(inventory.getPlayerUuid()).isEqualTo(playerUuid.toString());
            assertThat(inventory.getWorldGroup()).isEqualTo("survival_worlds");
            assertThat(inventory.getInventoryData()).isEqualTo("inv_data_base64");
            assertThat(inventory.getArmorData()).isEqualTo("armor_data_base64");
            assertThat(inventory.getOffHandData()).isEqualTo("offhand_data_base64");
            assertThat(inventory.getEnderChestData()).isEqualTo("enderchest_data_base64");
            assertThat(inventory.getExperienceLevel()).isEqualTo(35);
            assertThat(inventory.getExperiencePoints()).isEqualTo(0.65f);
            assertThat(inventory.getHealth()).isEqualTo(18.5);
            assertThat(inventory.getMaxHealth()).isEqualTo(20.0);
            assertThat(inventory.getFoodLevel()).isEqualTo(17);
            assertThat(inventory.getSaturation()).isEqualTo(4.2f);
            assertThat(inventory.getEffectsData()).isEqualTo("SPEED,300,0,false,true");
        }
    }

    @Nested
    @DisplayName("CanEqual")
    class CanEqualTests {

        @Test
        @DisplayName("canEqual should return true for same type")
        void canEqualSameType() {
            WorldInventory inv1 = new WorldInventory();
            WorldInventory inv2 = new WorldInventory();

            assertThat(inv1.canEqual(inv2)).isTrue();
        }

        @Test
        @DisplayName("canEqual should return false for different type")
        void canEqualDifferentType() {
            WorldInventory inv = new WorldInventory();

            assertThat(inv.canEqual("not an inventory")).isFalse();
        }
    }
}
