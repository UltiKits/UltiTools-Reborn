package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * GridView 对应的 Element。
 *
 * @author UltiTools Team
 * @version 1.0.0
 * @since 6.2.0
 */
public class GridViewElement extends Element {

    private final List<Element> childElements = new ArrayList<>();

    public GridViewElement(@NotNull GridView<?> widget) {
        super(widget);
    }

    @Override
    public void mount(@Nullable Element parent) {
        super.mount(parent);
        mountChildren();
    }

    @Override
    public void update(@NotNull Widget newWidget) {
        super.update(newWidget);
        updateChildren();
    }

    @Override
    public void performRebuild() {
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

    private void mountChildren() {
        GridView<?> gridView = (GridView<?>) getWidget();
        for (Widget childWidget : gridView.getChildren()) {
            Element childElement = childWidget.createElement();
            childElement.mount(this);
            childElements.add(childElement);
            addChild(childElement);
        }
    }

    private void updateChildren() {
        GridView<?> gridView = (GridView<?>) getWidget();
        List<Widget> newChildren = gridView.getChildren();

        int commonLength = Math.min(childElements.size(), newChildren.size());

        // 更新现有的
        for (int i = 0; i < commonLength; i++) {
            Element oldChild = childElements.get(i);
            Widget newWidget = newChildren.get(i);

            if (oldChild.canUpdate(newWidget)) {
                oldChild.update(newWidget);
            } else {
                oldChild.unmount();
                Element newChild = newWidget.createElement();
                newChild.mount(this);
                childElements.set(i, newChild);
            }
        }

        // 添加新增的
        for (int i = commonLength; i < newChildren.size(); i++) {
            Element newChild = newChildren.get(i).createElement();
            newChild.mount(this);
            childElements.add(newChild);
        }

        // 移除多余的
        while (childElements.size() > newChildren.size()) {
            Element removed = childElements.remove(childElements.size() - 1);
            removed.unmount();
        }
    }
}
