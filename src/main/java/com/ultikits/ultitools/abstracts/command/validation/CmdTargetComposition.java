package com.ultikits.ultitools.abstracts.command.validation;

import com.ultikits.ultitools.annotations.command.CmdTarget;
import com.ultikits.ultitools.annotations.command.CmdTarget.CmdTargetType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The single implementation of {@code @CmdTarget}'s class-versus-method composition rule.
 * <p>
 * Before this class existed, the rule was implemented twice and disagreed with itself:
 * {@code AbstractCommandExecutor} (removed in 6.3.0) required both the class-level and
 * method-level checks to pass (intersection), while {@code SenderTypeValidator} let the
 * method-level value fully replace the class-level one with no check at all (unguarded
 * override). Migrating a command class between the two executor generations silently changed
 * who could invoke it - no compiler signal on either side. See issue #320 and D-01 in
 * {@code .planning/phases/01-adjudication-foundations-compatibility-baseline/01-ADJUDICATION.md}.
 * <p>
 * D-01 settles this as <b>narrowing-only override</b>: a method-level value may only narrow the
 * class-level one. {@link CmdTargetType} has three values and is not a total order, so there are
 * three outcomes for a differing pair, not two - {@link Transition#WIDENING} and
 * {@link Transition#LATERAL} are both refused, and are refused identically. Treating
 * PLAYER-to-CONSOLE as a widening would be arbitrary; treating it as legal would let a
 * class-level PLAYER restriction be turned into a CONSOLE-only method with no signal at all. The
 * legal cases - {@link Transition#SAME} and {@link Transition#NARROWING} - are exactly the cases
 * where the old intersection and override readings already agreed, which is what makes migrating
 * base class behaviour-preserving for them.
 * <p>
 * {@link #classify} is the cheap, string-free predicate; {@link #check} is the diagnostic that
 * builds a description only for a method that actually violates - the same split
 * {@code aop/AopEligibility} uses, for the same reason: building a remediation string for every
 * method and discarding most of them is wasted work on the plugin-load path.
 *
 * @author wisdomme
 * @since 6.3.0
 */
public final class CmdTargetComposition {

    private CmdTargetComposition() {
        // Utility class
    }

    /**
     * How a method-level {@code @CmdTarget} value relates to the class-level one.
     * <p>
     * {@code CmdTargetType} is not a total order: BOTH is a superset of PLAYER and of CONSOLE,
     * but PLAYER and CONSOLE are not comparable to each other. That is why this is a four-way
     * enum rather than a two-way "same or different" - collapsing PLAYER-to-CONSOLE into
     * WIDENING would be an arbitrary choice with no basis in the type's own structure.
     */
    public enum Transition {
        /** Class-level and method-level values are identical. Always legal. */
        SAME,
        /** BOTH narrows to PLAYER, or BOTH narrows to CONSOLE. Always legal. */
        NARROWING,
        /** PLAYER widens to BOTH, or CONSOLE widens to BOTH. Ambiguous - refused at load. */
        WIDENING,
        /** PLAYER switches to CONSOLE, or CONSOLE switches to PLAYER. Ambiguous - refused at load. */
        LATERAL
    }

    /**
     * Classifies the relationship between a class-level and a method-level {@code @CmdTarget}
     * value.
     * <p>
     * Implemented as an exhaustive three-by-three decision rather than an ordinal comparison on
     * {@link CmdTargetType}: an ordinal comparison is exactly how the lateral case would get
     * silently folded into narrowing or widening depending on declaration order, which is not a
     * decision anyone made on purpose.
     *
     * @param classLevel  the class-level {@code @CmdTarget} value; absent means BOTH
     * @param methodLevel the method-level {@code @CmdTarget} value
     * @return the transition kind
     */
    public static Transition classify(CmdTargetType classLevel, CmdTargetType methodLevel) {
        if (classLevel == methodLevel) {
            return Transition.SAME;
        }
        if (classLevel == CmdTargetType.BOTH) {
            // BOTH -> PLAYER or BOTH -> CONSOLE
            return Transition.NARROWING;
        }
        if (methodLevel == CmdTargetType.BOTH) {
            // PLAYER -> BOTH or CONSOLE -> BOTH
            return Transition.WIDENING;
        }
        // The only remaining pairs are PLAYER -> CONSOLE and CONSOLE -> PLAYER.
        return Transition.LATERAL;
    }

    /**
     * Resolves the effective sender type for one invocation.
     * <p>
     * This is the per-invocation path: by the time it runs, {@link #check} has already refused
     * any class whose composition is ambiguous, so this method does no classification, no string
     * work, and throws nothing - it only reads the method's own annotation, if any.
     *
     * @param classLevel the class-level {@code @CmdTarget} value; pass BOTH when the class
     *                   carries no annotation
     * @param method     the matched command method; may carry a method-level {@code @CmdTarget}
     * @return the method-level value when present, otherwise {@code classLevel}
     */
    public static CmdTargetType resolve(CmdTargetType classLevel, Method method) {
        if (method != null && method.isAnnotationPresent(CmdTarget.class)) {
            return method.getAnnotation(CmdTarget.class).value();
        }
        return classLevel;
    }

    /**
     * Checks a command class for ambiguous {@code @CmdTarget} composition.
     * <p>
     * Pure reflection - no instance required, safe to call before the class becomes a bean
     * definition. Absent class-level annotation is treated as BOTH, so a method-level
     * restriction on an otherwise-unrestricted class is always a narrowing, never a violation.
     *
     * @param commandClass the command class to check
     * @return one human-readable violation per WIDENING or LATERAL method; empty for a legal class
     */
    public static List<String> check(Class<?> commandClass) {
        CmdTargetType classLevel = commandClass.isAnnotationPresent(CmdTarget.class)
                ? commandClass.getAnnotation(CmdTarget.class).value()
                : CmdTargetType.BOTH;

        List<String> violations = new ArrayList<>();
        for (Method method : commandClass.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(CmdTarget.class)) {
                continue;
            }
            CmdTargetType methodLevel = method.getAnnotation(CmdTarget.class).value();
            Transition transition = classify(classLevel, methodLevel);
            if (transition == Transition.WIDENING || transition == Transition.LATERAL) {
                violations.add(describe(commandClass, method, classLevel, methodLevel, transition));
            }
        }
        return violations;
    }

    /**
     * Builds the violation description for one offending method. Only called for a method that
     * has already been classified as WIDENING or LATERAL - never built speculatively for every
     * annotated method, per {@code aop/AopEligibility}'s house precedent.
     */
    private static String describe(Class<?> commandClass, Method method, CmdTargetType classLevel,
                                     CmdTargetType methodLevel, Transition transition) {
        return String.format(
                "%s#%s: class-level @CmdTarget(%s) and method-level @CmdTarget(%s) is a %s "
                        + "transition, which is not a narrowing and is refused",
                commandClass.getName(), method.getName(), classLevel, methodLevel, transition);
    }
}
