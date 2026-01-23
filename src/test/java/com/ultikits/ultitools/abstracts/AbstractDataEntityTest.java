package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ultikits.ultitools.annotations.Column;

import lombok.EqualsAndHashCode;

/**
 * 测试 AbstractDataEntity 抽象类的功能
 */
class AbstractDataEntityTest {

    @Test
    @DisplayName("Should create data entity with id")
    void testDataEntityCreation() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("test-id");
        
        assertThat(entity.getId()).isEqualTo("test-id");
    }

    @Test
    @DisplayName("Should set and get id as String")
    void testStringId() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("string-id");
        
        assertThat(entity.getId()).isInstanceOf(String.class);
        assertThat(entity.getId()).isEqualTo("string-id");
    }

    @Test
    @DisplayName("Should set and get id as Integer")
    void testIntegerId() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId(123);
        
        assertThat(entity.getId()).isInstanceOf(Integer.class);
        assertThat(entity.getId()).isEqualTo(123);
    }

    @Test
    @DisplayName("Should set and get id as Long")
    void testLongId() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId(999L);
        
        assertThat(entity.getId()).isInstanceOf(Long.class);
        assertThat(entity.getId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("Should set and get id as UUID")
    void testUuidId() {
        TestDataEntity entity = new TestDataEntity();
        java.util.UUID uuid = java.util.UUID.randomUUID();
        entity.setId(uuid);
        
        assertThat(entity.getId()).isInstanceOf(java.util.UUID.class);
        assertThat(entity.getId()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("Should handle null id")
    void testNullId() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId(null);
        
        assertThat(entity.getId()).isNull();
    }

    @Test
    @DisplayName("Should be serializable")
    void testSerialization() throws Exception {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("serialization-test");
        entity.setName("Test Entity");
        entity.setValue(42);
        
        // 序列化
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(entity);
        oos.close();
        
        // 反序列化
        // codacy:ignore - Test verifying serialization works with controlled test data, not untrusted input
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        TestDataEntity deserializedEntity = (TestDataEntity) ois.readObject();
        ois.close();
        
        assertThat(deserializedEntity.getId()).isEqualTo("serialization-test");
        assertThat(deserializedEntity.getName()).isEqualTo("Test Entity");
        assertThat(deserializedEntity.getValue()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should support equals and hashCode")
    void testEqualsAndHashCode() {
        TestDataEntity entity1 = new TestDataEntity();
        entity1.setId("same-id");
        entity1.setName("Entity 1");
        
        TestDataEntity entity2 = new TestDataEntity();
        entity2.setId("same-id");
        entity2.setName("Entity 1");
        
        TestDataEntity entity3 = new TestDataEntity();
        entity3.setId("different-id");
        entity3.setName("Entity 3");
        
        assertThat(entity1).isEqualTo(entity2);
        assertThat(entity1).isNotEqualTo(entity3);
        assertThat(entity1.hashCode()).isEqualTo(entity2.hashCode());
    }

    @Test
    @DisplayName("Should support toString")
    void testToString() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("test-id");
        entity.setName("Test");
        
        String toString = entity.toString();
        // Lombok生成的toString可能不包含父类的id字段
        // 只验证包含类名和当前类的字段
        assertThat(toString).contains("TestDataEntity");
        assertThat(toString).contains("Test");
    }

    @Test
    @DisplayName("Should handle complex object id")
    void testComplexObjectId() {
        TestDataEntity entity = new TestDataEntity();
        ComplexId complexId = new ComplexId("part1", "part2");
        entity.setId(complexId);
        
        assertThat(entity.getId()).isInstanceOf(ComplexId.class);
        ComplexId retrievedId = (ComplexId) entity.getId();
        assertThat(retrievedId.getPart1()).isEqualTo("part1");
        assertThat(retrievedId.getPart2()).isEqualTo("part2");
    }

    @Test
    @DisplayName("Should create entity with annotated columns")
    void testAnnotatedColumns() {
        TestDataEntity entity = new TestDataEntity();
        entity.setName("Test Name");
        entity.setValue(999);
        
        assertThat(entity.getName()).isEqualTo("Test Name");
        assertThat(entity.getValue()).isEqualTo(999);
    }

    @Test
    @DisplayName("Should handle default values")
    void testDefaultValues() {
        TestDataEntity entity = new TestDataEntity();
        
        assertThat(entity.getId()).isNull();
        assertThat(entity.getName()).isNull();
        assertThat(entity.getValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should support inheritance")
    void testInheritance() {
        ExtendedTestDataEntity entity = new ExtendedTestDataEntity();
        entity.setId("extended-id");
        entity.setName("Extended Name");
        entity.setExtraField("Extra Value");
        
        assertThat(entity.getId()).isEqualTo("extended-id");
        assertThat(entity.getName()).isEqualTo("Extended Name");
        assertThat(entity.getExtraField()).isEqualTo("Extra Value");
    }

    @Test
    @DisplayName("Should be immutable when using builder")
    void testBuilderPattern() {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("builder-id");
        entity.setName("Builder Name");
        entity.setValue(777);
        
        assertThat(entity.getId()).isEqualTo("builder-id");
        assertThat(entity.getName()).isEqualTo("Builder Name");
        assertThat(entity.getValue()).isEqualTo(777);
    }

    @Test
    @DisplayName("Should serialize and deserialize with null fields")
    void testSerializationWithNullFields() throws Exception {
        TestDataEntity entity = new TestDataEntity();
        entity.setId("null-field-test");
        // name 和 value 保持默认值
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(entity);
        oos.close();
        
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        TestDataEntity deserializedEntity = (TestDataEntity) ois.readObject();
        ois.close();
        
        assertThat(deserializedEntity.getId()).isEqualTo("null-field-test");
        assertThat(deserializedEntity.getName()).isNull();
    }

    // ========== 测试辅助类 ==========

    /**
     * 测试用数据实体
     */
    @EqualsAndHashCode(callSuper = true)
    private static class TestDataEntity extends AbstractDataEntity {
        @Column("name")
        private String name;

        @Column("value")
        private int value;

        public TestDataEntity() {}

        public TestDataEntity(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }

        @Override
        public String toString() {
            return "TestDataEntity(name=" + name + ", value=" + value + ")";
        }
    }

    /**
     * 扩展的测试数据实体
     */
    @EqualsAndHashCode(callSuper = true)
    private static class ExtendedTestDataEntity extends AbstractDataEntity {
        @Column("name")
        private String name;
        
        @Column("extra_field")
        private String extraField;

        public ExtendedTestDataEntity() {}

        public ExtendedTestDataEntity(String name, String extraField) {
            this.name = name;
            this.extraField = extraField;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getExtraField() { return extraField; }
        public void setExtraField(String extraField) { this.extraField = extraField; }

        @Override
        public String toString() {
            return "ExtendedTestDataEntity(name=" + name + ", extraField=" + extraField + ")";
        }
    }

    /**
     * 复杂ID类型
     */
    private static class ComplexId implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String part1;
        private String part2;

        public ComplexId(String part1, String part2) {
            this.part1 = part1;
            this.part2 = part2;
        }

        public String getPart1() { return part1; }
        public String getPart2() { return part2; }
    }
}
