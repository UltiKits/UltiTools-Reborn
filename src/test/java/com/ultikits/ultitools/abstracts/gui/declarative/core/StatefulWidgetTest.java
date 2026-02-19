package com.ultikits.ultitools.abstracts.gui.declarative.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StatefulWidget 和 State 测试。
 */
public class StatefulWidgetTest {

    @Test
    void testStateCreation() {
        TestStatefulWidget widget = new TestStatefulWidget();
        State<StatefulWidget> state = widget.createState();

        assertNotNull(state);
        assertTrue(state instanceof TestState);
    }

    @Test
    void testStateLifecycle() {
        TestStatefulWidget widget = new TestStatefulWidget();
        TestState state = (TestState) widget.createState();

        // 初始状态
        assertFalse(state.isMounted());

        // 模拟挂载
        state.setWidget(widget);
        state.setMounted(true);

        assertTrue(state.isMounted());
        assertEquals(widget, state.getWidget());

        // initState 被调用
        state.initState();
        assertTrue(state.initStateCalled);

        // 模拟 dispose
        state.dispose();
        assertFalse(state.isMounted());
        assertTrue(state.disposeCalled);
    }

    @Test
    void testSetStateTriggersRebuild() {
        TestStatefulWidget widget = new TestStatefulWidget();
        TestState state = (TestState) widget.createState();

        // 需要挂载才能 setState
        state.setWidget(widget);
        state.setMounted(true);

        TestElement element = new TestElement(widget);
        state.setElement(element);

        assertFalse(element.markNeedsBuildCalled);

        state.setState(() -> {
            state.counter = 42;
        });

        assertTrue(element.markNeedsBuildCalled);
        assertEquals(42, state.counter);
    }

    @Test
    void testSetStateWithoutMountThrows() {
        TestStatefulWidget widget = new TestStatefulWidget();
        TestState state = (TestState) widget.createState();

        // 未挂载时调用 setState 应该抛出异常
        assertThrows(IllegalStateException.class, () -> {
            state.setState(() -> {
            });
        });
    }

    @Test
    void testDidUpdateWidget() {
        TestStatefulWidget oldWidget = new TestStatefulWidget();
        TestStatefulWidget newWidget = new TestStatefulWidget();
        TestState state = (TestState) oldWidget.createState();

        state.setWidget(oldWidget);
        state.setMounted(true);

        // 模拟 widget 更新
        state.setWidget(newWidget);
        state.didUpdateWidget(oldWidget);

        assertTrue(state.didUpdateWidgetCalled);
    }

    @Test
    void testGetWidgetBeforeMountThrows() {
        TestStatefulWidget widget = new TestStatefulWidget();
        TestState state = (TestState) widget.createState();

        assertThrows(IllegalStateException.class, state::getWidget);
    }

    // 测试用的 StatefulWidget
    private static class TestStatefulWidget extends StatefulWidget {
        @Override
        public State<StatefulWidget> createState() {
            return new TestState();
        }
    }

    // 测试用的 State
    private static class TestState extends State<StatefulWidget> {
        boolean initStateCalled = false;
        boolean didUpdateWidgetCalled = false;
        boolean disposeCalled = false;
        int counter = 0;

        @Override
        protected void initState() {
            super.initState();
            initStateCalled = true;
        }

        @Override
        protected void didUpdateWidget(StatefulWidget oldWidget) {
            super.didUpdateWidget(oldWidget);
            didUpdateWidgetCalled = true;
        }

        @Override
        protected void dispose() {
            super.dispose();
            disposeCalled = true;
        }

        @Override
        public Widget build(BuildContext context) {
            return null;
        }
    }

    // 测试用的 Element
    private static class TestElement extends StatefulElement {
        boolean markNeedsBuildCalled = false;

        TestElement(StatefulWidget widget) {
            super(widget);
        }

        @Override
        public void markNeedsBuild() {
            markNeedsBuildCalled = true;
        }

        @Override
        public void performRebuild() {
        }
    }
}
