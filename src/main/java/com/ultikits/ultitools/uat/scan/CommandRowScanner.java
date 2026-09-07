package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.uat.SurfaceRow;
import com.ultikits.ultitools.utils.ReflectionUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code command} and {@code help} surface rows from loaded {@code @CmdExecutor}
 * classes (Phase 10, D-10-04), mirroring the annotation-driven decision idiom
 * {@code CommandManager.registerAll} already uses — minus the live container, since the
 * extractor walks classes directly rather than beans.
 * <p>
 * Emits exactly one {@code command} row per {@code @CmdMapping} format found by
 * {@link ReflectionUtil#getAllMethods(Class)}, plus exactly one {@code help} row per
 * {@code @CmdExecutor} class (the {@code handleHelp} output is the first thing a player types).
 * Class-level annotation lookup goes through {@link MergedAnnotationResolver#find(Class, Class)}
 * so {@code @AliasFor} resolves exactly as it does at runtime; method/parameter annotations are
 * read directly since none of {@code @CmdMapping}/{@code @CmdParam}/{@code @CmdSender}/
 * {@code @CmdCD}/{@code @UsageLimit} declare an alias.
 * <p>
 * Collision detection lives here, not in {@link RowId}: two rows computing the same id raise
 * {@link ExtractorException} naming both fully qualified classes — nothing is ever merged.
 *
 * @since 6.3.0
 */
public final class CommandRowScanner {

    private static final String KIND_COMMAND = "command";
    private static final String KIND_HELP = "help";
    private static final String HELP_MEMBER = "handleHelp";
    private static final String HELP_FORMAT = "help";

    /**
     * Scans {@code classes} for {@code @CmdExecutor} types and emits their command and help rows.
     *
     * @param origin  the module (or {@code "framework"}) these classes belong to
     * @param classes the loaded (uninitialized) classes to scan
     * @return every emitted row, in scan order (not yet sorted by id — the writer sorts)
     * @throws ExtractorException on a row-id collision between two distinct classes
     */
    public List<SurfaceRow> scan(String origin, List<Class<?>> classes) throws ExtractorException {
        List<SurfaceRow> rows = new ArrayList<>();
        Map<String, String> idOwners = new LinkedHashMap<>();
        for (Class<?> clazz : classes) {
            CmdExecutor executor = MergedAnnotationResolver.find(clazz, CmdExecutor.class);
            if (executor == null) {
                continue;
            }
            CmdTarget classTarget = MergedAnnotationResolver.find(clazz, CmdTarget.class);
            for (Method method : ReflectionUtil.getAllMethods(clazz)) {
                CmdMapping mapping = method.getAnnotation(CmdMapping.class);
                if (mapping == null) {
                    continue;
                }
                SurfaceRow row = buildCommandRow(origin, clazz, executor, classTarget, method, mapping);
                claim(idOwners, row.getId(), clazz.getName());
                rows.add(row);
            }
            SurfaceRow help = buildHelpRow(origin, clazz, executor, classTarget);
            claim(idOwners, help.getId(), clazz.getName());
            rows.add(help);
        }
        return rows;
    }

    private static void claim(Map<String, String> idOwners, String id, String fullyQualifiedClassName)
            throws ExtractorException {
        String existingOwner = idOwners.putIfAbsent(id, fullyQualifiedClassName);
        if (existingOwner != null) {
            throw ExtractorException.idCollision(id, existingOwner, fullyQualifiedClassName);
        }
    }

    private SurfaceRow buildCommandRow(String origin, Class<?> clazz, CmdExecutor executor,
            CmdTarget classTarget, Method method, CmdMapping mapping) {
        String cls = clazz.getSimpleName();
        String member = method.getName();
        String format = mapping.format();
        String id = RowId.of(KIND_COMMAND, origin, cls, member, format);

        List<Map<String, Object>> params = new ArrayList<>();
        List<String> senders = new ArrayList<>();
        collectParamsAndSenders(method, params, senders);

        CmdTarget methodTarget = method.getAnnotation(CmdTarget.class);
        CmdTarget effectiveTarget = methodTarget != null ? methodTarget : classTarget;

        // ReflectionUtil.resolveMethodOrClassAnnotation, not a plain method.getAnnotation:
        // CooldownValidator/UsageLockValidator resolve @CmdCD/@UsageLimit in three steps
        // (method, then the concrete executor class directly, then the mapping method's own
        // declaring class directly) precisely because neither annotation is @Inherited --
        // an inherited, unoverridden @CmdMapping method's declaring class is whatever
        // ancestor first declared it, never the concrete subclass a class-level annotation
        // might sit on. Reading only the method here silently dropped a real, enforced
        // class-level limit from the generated row.
        CmdCD cooldown = ReflectionUtil.resolveMethodOrClassAnnotation(method, clazz, CmdCD.class);
        UsageLimit usageLimit = ReflectionUtil.resolveMethodOrClassAnnotation(method, clazz, UsageLimit.class);

        boolean requireOp = executor.requireOp() || mapping.requireOp();
        String permission = !mapping.permission().isEmpty() ? mapping.permission() : executor.permission();
        String trigger = trigger(executor, format);

        return SurfaceRow.builder()
                .id(id)
                .kind(KIND_COMMAND)
                .origin(origin)
                .cls(cls)
                .className(clazz.getName())
                .member(member)
                .format(format)
                .aliases(Arrays.asList(executor.alias()))
                .permission(permission)
                .requireOp(requireOp)
                .manualRegister(executor.manualRegister())
                .cmdTarget(effectiveTarget != null ? effectiveTarget.value().name() : null)
                .params(params)
                .senders(senders)
                .cooldownSeconds(cooldown != null ? cooldown.value() : null)
                .usageLimit(usageLimit != null ? usageLimit.value().name() : null)
                .trigger(trigger)
                .build();
    }

    private SurfaceRow buildHelpRow(String origin, Class<?> clazz, CmdExecutor executor, CmdTarget classTarget) {
        String cls = clazz.getSimpleName();
        String id = RowId.of(KIND_HELP, origin, cls, HELP_MEMBER, HELP_FORMAT);
        String trigger = trigger(executor, HELP_FORMAT);

        return SurfaceRow.builder()
                .id(id)
                .kind(KIND_HELP)
                .origin(origin)
                .cls(cls)
                .className(clazz.getName())
                .member(HELP_MEMBER)
                .format(HELP_FORMAT)
                .aliases(Arrays.asList(executor.alias()))
                .permission(executor.permission())
                .requireOp(executor.requireOp())
                .manualRegister(executor.manualRegister())
                .cmdTarget(classTarget != null ? classTarget.value().name() : null)
                .params(Collections.emptyList())
                .senders(Collections.emptyList())
                .cooldownSeconds(null)
                .usageLimit(null)
                .trigger(trigger)
                .build();
    }

    private static void collectParamsAndSenders(Method method, List<Map<String, Object>> params,
            List<String> senders) {
        for (Parameter parameter : method.getParameters()) {
            CmdSender cmdSender = parameter.getAnnotation(CmdSender.class);
            if (cmdSender != null) {
                senders.add(parameter.getType().getSimpleName());
                continue;
            }
            CmdParam cmdParam = parameter.getAnnotation(CmdParam.class);
            if (cmdParam != null) {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", cmdParam.value());
                param.put("type", parameter.getType().getSimpleName());
                param.put("suggest", cmdParam.suggest());
                params.add(param);
            }
        }
    }

    private static String trigger(CmdExecutor executor, String format) {
        String[] alias = executor.alias();
        String firstAlias = alias.length > 0 ? alias[0] : "";
        return format.isEmpty() ? "/" + firstAlias : "/" + firstAlias + " " + format;
    }
}
