package com.ultikits.ultitools.uat.scan;

import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.abstracts.command.validation.CmdTargetComposition;
import com.ultikits.ultitools.annotations.command.CmdCD;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.annotations.command.CmdSender;
import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.UsageLimit;
import com.ultikits.ultitools.uat.ExtractorException;
import com.ultikits.ultitools.uat.RowId;
import com.ultikits.ultitools.uat.SurfaceRow;
import com.ultikits.ultitools.utils.ReflectionUtil;

import org.bukkit.command.CommandExecutor;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts {@code command} and {@code help} surface rows from loaded {@code @CmdExecutor}
 * classes (Phase 10, D-10-04), mirroring the annotation-driven decision idiom
 * {@code CommandManager.registerAll} already uses — minus the live container, since the
 * extractor walks classes directly rather than beans.
 * <p>
 * Emits exactly one {@code command} row per {@code @CmdMapping} format found by
 * {@link ReflectionUtil#getAllMethods(Class)}, plus exactly one {@code help} row per
 * {@code @CmdExecutor} class (the {@code handleHelp} output is the first thing a player types).
 * <p>
 * Class-level {@code @CmdExecutor} and {@code @CmdTarget} are both read via plain
 * {@link Class#getAnnotation(Class)}, not a hierarchy-or-meta-annotation-walking resolver
 * (Codex review of PR #427) — matching {@code CommandManager.register}'s own direct
 * {@code isAnnotationPresent(CmdExecutor.class)} and {@code BaseCommandExecutor.
 * createDefaultValidatorChain}'s own direct {@code this.getClass().getAnnotation
 * (CmdTarget.class)} exactly. Neither annotation is {@code @Inherited}: an unannotated
 * subclass of an {@code @CmdExecutor} superclass is never registered as a Bukkit command at
 * all (the runtime logs a warning and does nothing), so this scanner emits no row for it
 * rather than a phantom one; a concrete executor that redeclares {@code @CmdExecutor} but
 * inherits a class-level {@code @CmdTarget} from its superclass is treated by the runtime's
 * own direct lookup as carrying NO class-level target restriction ({@code BOTH}, via
 * {@code SenderTypeValidator.fromAnnotation(null)}), not the ancestor's restriction, so this
 * scanner must not report one either.
 * <p>
 * When two methods on the same class declare {@code @CmdMapping} with the identical
 * {@code format()}, only the FIRST one (in {@link ReflectionUtil#getAllMethods(Class)}'s own
 * order) produces a row — matching {@code BaseCommandExecutor.scanCommandMappings}'s own
 * {@code mappings.putIfAbsent(mapping.format(), method)}, under which the second method is
 * never reachable at runtime no matter how it is invoked.
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
            CmdExecutor executor = clazz.getAnnotation(CmdExecutor.class);
            // CommandManager.registerAll/registerAllExternal both discover commands
            // exclusively through getBeanNamesForType(CommandExecutor.class) (Bukkit's own
            // interface) -- @CmdExecutor itself enforces no such supertype, so a class
            // carrying the annotation but not implementing it is never returned by that
            // lookup and none of its rows could ever actually be exercised.
            if (executor == null || !CommandExecutor.class.isAssignableFrom(clazz)) {
                continue;
            }
            // ComponentScanner.registerComponent runs CmdTargetComposition.check BEFORE
            // registerBeanDefinition -- a WIDENING or LATERAL class-versus-method @CmdTarget
            // transition (e.g. class-level PLAYER with a method-level CONSOLE) refuses the
            // ENTIRE class, not just the offending mapping: no bean is registered at all, so
            // neither its commands nor its help output are ever reachable (Codex review of PR
            // #427). Reusing the same check here, not reimplementing it, guarantees this
            // scanner can never drift from what actually gets refused.
            if (!CmdTargetComposition.check(clazz).isEmpty()) {
                continue;
            }
            CmdTarget classTarget = clazz.getAnnotation(CmdTarget.class);
            // BaseCommandExecutor.scanCommandMappings keys its own mappings map by
            // mapping.format() via putIfAbsent -- so if two methods on this same class declare
            // the identical format string, only the first one (in this same getAllMethods()
            // order) is ever reachable at runtime; the second occupies a format string that
            // already resolved to a different method. Emitting a row for it would ask a
            // real-machine session to exercise a subcommand that can never actually dispatch.
            Set<String> claimedFormats = new LinkedHashSet<>();
            boolean helpDispatchShadowsMapping = isDefaultHelpCommand(clazz);
            for (Method method : ReflectionUtil.getAllMethods(clazz)) {
                CmdMapping mapping = method.getAnnotation(CmdMapping.class);
                if (mapping == null || !claimedFormats.add(mapping.format())) {
                    continue;
                }
                // BaseCommandExecutor.onCommand intercepts a single-token "help" argument
                // BEFORE matchMethod ever runs, dispatching to the synthesized help row
                // instead -- a @CmdMapping(format = "help") method (a real, currently-shipping
                // pattern: UltiToolsCommands declares exactly this) is therefore never
                // reachable when getHelpCommand() has not been overridden away from its
                // default "help" (Codex review of PR #427). Only excluded in that unambiguous
                // case: if some class between this one and BaseCommandExecutor DOES override
                // getHelpCommand(), this scanner cannot know what it returns without executing
                // it, and silently DROPPING a possibly-legitimate row is worse than a phantom
                // one an executor can mark blocked.
                if (helpDispatchShadowsMapping && "help".equals(mapping.format())) {
                    continue;
                }
                SurfaceRow row = buildCommandRow(origin, clazz, executor, classTarget, method, mapping);
                claim(idOwners, row.getId(), clazz.getName());
                rows.add(row);
            }
            SurfaceRow help = buildHelpRow(origin, clazz, executor, classTarget, helpDispatchShadowsMapping);
            claim(idOwners, help.getId(), clazz.getName());
            rows.add(help);
        }
        return rows;
    }

    /**
     * True when {@code clazz} resolves {@code getHelpCommand()} to {@code BaseCommandExecutor}'s
     * own default (i.e. nothing between {@code clazz} and {@code BaseCommandExecutor} overrides
     * it) -- the unambiguous case in which the string {@code "help"} definitely triggers
     * {@code onCommand}'s single-token help shortcut. Uses {@link ReflectionUtil#getAllMethods}
     * (which already resolves overrides correctly) rather than {@code Class#getMethod}, since
     * {@code getHelpCommand()} is {@code protected} and {@code getMethod} only ever finds
     * {@code public} members.
     */
    private static boolean isDefaultHelpCommand(Class<?> clazz) {
        for (Method method : ReflectionUtil.getAllMethods(clazz)) {
            if (method.getName().equals("getHelpCommand") && method.getParameterCount() == 0) {
                return method.getDeclaringClass() == BaseCommandExecutor.class;
            }
        }
        return false;
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
        // PermissionValidator checks the class-level (@CmdExecutor) and method-level
        // (@CmdMapping) permission CONJUNCTIVELY -- both are required when both are declared,
        // exactly like requireOp above, never one overriding the other. Treating the mapping
        // permission as an override (the old `mapping.permission().isEmpty() ? ... :
        // executor.permission()` idiom) silently dropped a real, enforced class-level
        // prerequisite whenever a method also declared its own (Codex review of PR #427).
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
                .permission(executor.permission())
                .mappingPermission(mapping.permission())
                .requireOp(requireOp)
                .manualRegister(executor.manualRegister())
                .cmdTarget(effectiveTarget != null ? effectiveTarget.value().name() : null)
                .params(params)
                .senders(senders)
                .cooldownSeconds(cooldown != null ? cooldown.value() : null)
                .usageLimit(usageLimit != null ? usageLimit.value().name() : null)
                .usageLimitContainConsole(usageLimit != null ? usageLimit.ContainConsole() : null)
                .trigger(trigger)
                .build();
    }

    private SurfaceRow buildHelpRow(String origin, Class<?> clazz, CmdExecutor executor, CmdTarget classTarget,
            boolean helpCommandIsDefault) {
        String cls = clazz.getSimpleName();
        String id = RowId.of(KIND_HELP, origin, cls, HELP_MEMBER, HELP_FORMAT);
        // The literal "/alias help" is only actually true when getHelpCommand() has not been
        // overridden away from BaseCommandExecutor's own default (Codex review of PR #427,
        // discovered via the HelpFormatMappingWithOverriddenHelpCommand fixture the PREVIOUS
        // round's own regression test added): an override changes the real token
        // onCommand's single-token shortcut checks against, so "/alias help" would then
        // dispatch to a DIFFERENT method (a real @CmdMapping("help"), if one exists) while the
        // synthesized help behavior moves to whatever the override actually returns -- a value
        // this scanner cannot resolve without executing the method, which D-10-01 forbids.
        String trigger = helpCommandIsDefault
                ? trigger(executor, HELP_FORMAT)
                : "getHelpCommand() is overridden on this class -- the real help token cannot "
                        + "be statically resolved; do not assume it is \"help\"";

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
