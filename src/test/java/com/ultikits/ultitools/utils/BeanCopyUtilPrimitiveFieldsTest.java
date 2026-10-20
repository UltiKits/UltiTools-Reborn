package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.interfaces.impl.data.json.SimpleJsonDataOperator;

/**
 * Every primitive and wrapper field survives {@link BeanCopyUtil#copyProperties}, and therefore
 * survives {@code SimpleJsonDataOperator#update(T)} from a detached copy (#520).
 * <p>
 * A new class rather than an extension of {@code BeanCopyUtilTest}: that class pins the
 * conversion rules for mismatched types, this one pins the same-type round trip for the whole
 * primitive set at once, plus the JSON-store consumer the issue measured.
 */
@DisplayName("BeanCopyUtil copies every primitive and wrapper field (#520)")
class BeanCopyUtilPrimitiveFieldsTest {

    /** One field of each primitive type and each wrapper type. */
    public static class AllPrimitives {
        private boolean aBoolean;
        private char aChar;
        private byte aByte;
        private short aShort;
        private int anInt;
        private long aLong;
        private float aFloat;
        private double aDouble;
        private Boolean boxedBoolean;
        private Character boxedChar;
        private Byte boxedByte;
        private Short boxedShort;
        private Integer boxedInt;
        private Long boxedLong;
        private Float boxedFloat;
        private Double boxedDouble;
    }

    @Table("primitive_flag_entity")
    public static class FlagEntity extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("active")
        private boolean active;
        @Column("grade")
        private char grade;
        @Column("boxed_grade")
        private Character boxedGrade;

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public char getGrade() {
            return grade;
        }

        public void setGrade(char grade) {
            this.grade = grade;
        }

        public Character getBoxedGrade() {
            return boxedGrade;
        }

        public void setBoxedGrade(Character boxedGrade) {
            this.boxedGrade = boxedGrade;
        }
    }

    @Nested
    @DisplayName("copyProperties")
    class CopyProperties {

        @Test
        @DisplayName("copies a field of every primitive and wrapper type with its value intact")
        void copiesEveryPrimitiveAndWrapperField() {
            AllPrimitives source = new AllPrimitives();
            source.aBoolean = true;
            source.aChar = 'x';
            source.aByte = 7;
            source.aShort = 300;
            source.anInt = 70_000;
            source.aLong = 5_000_000_000L;
            source.aFloat = 1.5f;
            source.aDouble = 2.25d;
            source.boxedBoolean = Boolean.TRUE;
            source.boxedChar = 'y';
            source.boxedByte = 8;
            source.boxedShort = 301;
            source.boxedInt = 70_001;
            source.boxedLong = 5_000_000_001L;
            source.boxedFloat = 2.5f;
            source.boxedDouble = 3.25d;

            AllPrimitives target = new AllPrimitives();
            BeanCopyUtil.copyProperties(source, target);

            assertThat(target).usingRecursiveComparison().isEqualTo(source);
        }
    }

    @Nested
    @DisplayName("JSON store update(T) from a detached copy")
    class JsonStoreDetachedUpdate {

        @TempDir
        Path storeDir;

        private FlagEntity storedAndReloaded() {
            SimpleJsonDataOperator<FlagEntity> store =
                    new SimpleJsonDataOperator<>(storeDir.toFile().getAbsolutePath(), FlagEntity.class);
            FlagEntity original = new FlagEntity();
            original.setActive(true);
            original.setGrade('A');
            original.setBoxedGrade('B');
            store.insert(original);
            store.flush();
            return original;
        }

        private FlagEntity detachedCopyOf(FlagEntity entity) {
            FlagEntity copy = new FlagEntity();
            copy.setId(entity.getId());
            copy.setActive(entity.isActive());
            copy.setGrade(entity.getGrade());
            copy.setBoxedGrade(entity.getBoxedGrade());
            return copy;
        }

        private FlagEntity freshLoad(String id) {
            return new SimpleJsonDataOperator<>(storeDir.toFile().getAbsolutePath(), FlagEntity.class).getById(id);
        }

        @Test
        @DisplayName("a boolean changed from true to false on a detached copy reads back false after a fresh load")
        void booleanChangeOnDetachedCopyPersists() {
            FlagEntity original = storedAndReloaded();
            SimpleJsonDataOperator<FlagEntity> store =
                    new SimpleJsonDataOperator<>(storeDir.toFile().getAbsolutePath(), FlagEntity.class);

            FlagEntity copy = detachedCopyOf(original);
            copy.setActive(false);
            store.update(copy);
            store.flush();

            assertThat(freshLoad(original.getId()).isActive())
                    .as("the boolean change on the detached copy was dropped by update(T)")
                    .isFalse();
        }

        @Test
        @DisplayName("char and Character changes on a detached copy read back after a fresh load")
        void charChangesOnDetachedCopyPersist() {
            FlagEntity original = storedAndReloaded();
            SimpleJsonDataOperator<FlagEntity> store =
                    new SimpleJsonDataOperator<>(storeDir.toFile().getAbsolutePath(), FlagEntity.class);

            FlagEntity copy = detachedCopyOf(original);
            copy.setGrade('C');
            copy.setBoxedGrade('D');
            store.update(copy);
            store.flush();

            FlagEntity reloaded = freshLoad(original.getId());
            assertThat(reloaded.getGrade()).as("the char change was dropped").isEqualTo('C');
            assertThat(reloaded.getBoxedGrade()).as("the Character change was dropped").isEqualTo('D');
        }
    }
}
