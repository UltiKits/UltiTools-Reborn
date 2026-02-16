package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Widget 核心类测试。
 */
public class WidgetTest {

    @Test
    void testWidgetCreation() {
        TestWidget widget = new TestWidget();
        assertNull(widget.getKey());
    }

    @Test
    void testWidgetWithKey() {
        SlotKey key = SlotKey.of("test-key");
        TestWidgetWithKey widget = new TestWidgetWithKey(key);
        assertEquals(key, widget.getKey());
    }

    @Test
    void testWidgetEquality() {
        SlotKey key1 = SlotKey.of("key1");
        SlotKey key2 = SlotKey.of("key2");

        TestWidgetWithKey widget1a = new TestWidgetWithKey(key1);
        TestWidgetWithKey widget1b = new TestWidgetWithKey(key1);
        TestWidgetWithKey widget2 = new TestWidgetWithKey(key2);

        // 相同 key 的 widget 在 isSameWidget 中应该相等
        assertTrue(widget1a.isSameWidget(widget1b));
        assertFalse(widget1a.isSameWidget(widget2));
    }

    @Test
    void testWidgetCanUpdate() {
        TestWidget widget1 = new TestWidget();
        TestWidget widget2 = new TestWidget();

        Element element = widget1.createElement();

        assertTrue(widget1.canUpdate(element));
        assertTrue(widget2.canUpdate(element));
    }

    @Test
    void testDifferentWidgetTypesCannotUpdate() {
        TestWidget widget = new TestWidget();
        TestWidget2 widget2 = new TestWidget2();

        Element element = widget.createElement();

        assertFalse(widget2.canUpdate(element));
    }

    // 测试用的 Widget 类
    private static class TestWidget extends Widget {
        @Override
        public Element createElement() {
            return new TestElement(this);
        }
    }

    private static class TestWidgetWithKey extends Widget {
        TestWidgetWithKey(SlotKey key) {
            super(key);
        }

        @Override
        public Element createElement() {
            return new TestElement(this);
        }
    }

    private static class TestWidget2 extends Widget {
        @Override
        public Element createElement() {
            return new TestElement2(this);
        }
    }

    private static class TestElement extends Element {
        TestElement(Widget widget) {
            super(widget);
        }

        @Override
        public void performRebuild() {
            // super.performRebuild();
        }
    }

    private static class TestElement2 extends Element {
        TestElement2(Widget widget) {
            super(widget);
        }

        @Override
        public void performRebuild() {
            // super.performRebuild();
        }
    }
}
