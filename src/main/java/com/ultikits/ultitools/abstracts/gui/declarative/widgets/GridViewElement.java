package com.ultikits.ultitools.abstracts.gui.declarative.widgets;

import com.ultikits.ultitools.abstracts.gui.declarative.core.Element;
import com.ultikits.ultitools.abstracts.gui.declarative.core.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
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
        GridView<?> gridView = (GridView<?>) getWidget();
        List<Element> reconciled = updateChildren(childElements, gridView.getChildren());
        childElements.clear();
        childElements.addAll(reconciled);
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

    @Override
    @NotNull
    public List<Element> getChildren() {
        return Collections.unmodifiableList(childElements);
    }

    private void mountChildren() {
        GridView<?> gridView = (GridView<?>) getWidget();
        for (Widget childWidget : gridView.getChildren()) {
            Element childElement = childWidget.createElement();
            childElement.mount(this);
            childElements.add(childElement);
        }
    }
}
