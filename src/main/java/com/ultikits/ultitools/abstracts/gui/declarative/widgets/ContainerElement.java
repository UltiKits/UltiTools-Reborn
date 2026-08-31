package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container 对应的 Element。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class ContainerElement extends Element {

    private final List<Element> childElements = new ArrayList<>();

    public ContainerElement(@NotNull Container container) {
        super(container);
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);
        mountChildren();
    }

    @Override
    public void update(@NotNull Widget newWidget) {
        super.update(newWidget);
        Container container = (Container) getWidget();
        List<Element> reconciled = updateChildren(childElements, container.getChildren());
        childElements.clear();
        childElements.addAll(reconciled);
    }

    @Override
    public void performRebuild() {
        // Container 只是布局容器，不直接构建 RenderNode
        // 子元素的 RenderNode 会在收集阶段被处理
        for (Element child : childElements) {
            if (child.isDirty()) {
                child.performRebuild();
            }
        }
        clearDirty();
    }

    @Override
    public void unmount() {
        for (Element child : childElements) {
            child.unmount();
        }
        childElements.clear();
        super.unmount();
    }

    @Override
    @NotNull
    public List<Element> getChildren() {
        return Collections.unmodifiableList(childElements);
    }

    private void mountChildren() {
        Container container = (Container) getWidget();
        for (Widget childWidget : container.getChildren()) {
            Element childElement = childWidget.createElement();
            childElement.mount(this);
            childElements.add(childElement);
        }
    }
}
