package com.ultikits.ultitools.manager;

import com.ultikits.ultitools.scheduling.Scheduler;
import com.ultikits.ultitools.scheduling.TaskHandle;
import com.ultikits.ultitools.scheduling.TaskState;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Annotation-handle inspection: physical Paper deliveries are not annotation registrations. */
public final class TaskManagerTestAccess {
    private TaskManagerTestAccess() {
        // Test-only static inspection helper.
    }

    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    public static Set<TaskHandle> activeHandles(TaskManager manager) {
        try {
            Set<TaskHandle> handles = new LinkedHashSet<>();
            Field literals = TaskManager.class.getDeclaredField("scheduledTasks");
            literals.setAccessible(true);
            Map<Scheduler, List<TaskHandle>> registered = (Map<Scheduler, List<TaskHandle>>) literals.get(manager);
            registered.values().forEach(handles::addAll);
            Field bindings = TaskManager.class.getDeclaredField("boundTasks");
            bindings.setAccessible(true);
            Map<?, List<TaskManager.BoundTask>> bound = (Map<?, List<TaskManager.BoundTask>>) bindings.get(manager);
            bound.values().forEach(tasks -> tasks.forEach(task -> handles.add(task.task)));
            handles.removeIf(task -> task.state() != TaskState.SCHEDULED && task.state() != TaskState.RUNNING);
            return handles;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
