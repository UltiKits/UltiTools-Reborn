package com.ultikits.ultitools.command.engine;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.ultikits.ultitools.abstracts.command.BaseCommandExecutor;
import com.ultikits.ultitools.annotations.command.CmdExecutor;
import com.ultikits.ultitools.annotations.command.CmdMapping;
import com.ultikits.ultitools.annotations.command.CmdParam;
import com.ultikits.ultitools.context.MergedAnnotationResolver;
import com.ultikits.ultitools.utils.ReflectionUtil;

import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Compiles the annotation model once, never matching strings during normal dispatch. */
final class CommandTreeCompiler {
    private final NativeArgumentTypes nativeTypes;

    CommandTreeCompiler(NativeArgumentTypes nativeTypes) { this.nativeTypes = nativeTypes; }

    @FunctionalInterface interface Invocation {
        int run(CommandContext<CommandSourceStack> context, Method method, Map<String, Object> values)
                throws CommandSyntaxException;
    }

    CompiledCommand compile(BaseCommandExecutor executor, String description, Invocation invocation,
            Command<CommandSourceStack> help) {
        CmdExecutor annotation = MergedAnnotationResolver.find(executor.getClass(), CmdExecutor.class);
        if (annotation == null || annotation.alias().length == 0) {
            throw new CommandCompilationException(executor.getClass().getName() + ": missing @CmdExecutor aliases");
        }
        return compile(executor, description, Arrays.asList(annotation.alias()), annotation.permission(),
                annotation.requireOp(), invocation, help);
    }

    CompiledCommand compile(BaseCommandExecutor executor, String description, List<String> aliases,
            String permission, boolean requireOp, Invocation invocation, Command<CommandSourceStack> help) {
        rejectDuplicateFormats(executor);
        List<String> labels = aliases.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        Spec root = new Spec(labels.getFirst(), null, null);
        List<Method> methods = new ArrayList<>(executor.getMappings().values());
        methods.sort(Comparator.comparing(Method::toGenericString));
        for (Method method : methods) {
            CmdMapping mapping = method.getAnnotation(CmdMapping.class);
            if (mapping != null) add(root, executor.getClass(), method, mapping.format());
        }
        StringBuilder signature = new StringBuilder(labels.toString());
        signature.append('|').append(permission).append(':').append(requireOp);
        appendSignature(root, signature);
        LiteralCommandNode<CommandSourceStack> tree = (LiteralCommandNode<CommandSourceStack>)
                build(root, invocation, help, new SuggestionBridge(executor), List.of());
        if (tree.getCommand() == null) tree = copyRootWithHelp(tree, help);
        String helpLiteral = executor.helpCommand().toLowerCase(Locale.ROOT);
        if (tree.getChild(helpLiteral) == null) tree.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal(helpLiteral).executes(help).build());
        return new CompiledCommand(labels.getFirst(), labels.subList(1, labels.size()), description, tree, signature.toString());
    }

    private static LiteralCommandNode<CommandSourceStack> copyRootWithHelp(
            LiteralCommandNode<CommandSourceStack> root, Command<CommandSourceStack> help) {
        LiteralCommandNode<CommandSourceStack> copy = LiteralArgumentBuilder.<CommandSourceStack>literal(root.getLiteral())
                .executes(help).build();
        root.getChildren().forEach(copy::addChild);
        return copy;
    }

    private static void rejectDuplicateFormats(BaseCommandExecutor executor) {
        Map<String, Method> effective = executor.getMappings();
        for (Method method : ReflectionUtil.getAllMethods(executor.getClass())) {
            CmdMapping annotation = method.getAnnotation(CmdMapping.class);
            if (annotation == null) continue;
            Method winner = effective.get(annotation.format());
            // Subclass shadowing stays unchanged; only two effective declarations in one class conflict.
            if (winner != null && !winner.equals(method) && winner.getDeclaringClass().equals(method.getDeclaringClass())) {
                throw invalid(executor.getClass(), method, annotation.format(), "duplicate path with " + winner.getName());
            }
        }
    }

    private void add(Spec root, Class<?> owner, Method method, String format) {
        String[] parts = format.isBlank() ? new String[0] : format.trim().split("\\s+");
        Map<String, Parameter> parameters = parameters(method);
        Spec current = root;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            ArgumentMapping argument = null;
            String name = part.toLowerCase(Locale.ROOT);
            if (part.startsWith("<") && part.endsWith(">")) {
                boolean greedy = part.endsWith("...>");
                if (greedy && i != parts.length - 1) throw invalid(owner, method, format, "greedy parameter must be last");
                name = part.substring(1, part.length() - (greedy ? 4 : 1));
                Parameter parameter = parameters.get(name);
                if (parameter == null) throw invalid(owner, method, format, "missing @CmdParam for " + name);
                names.add(name);
                try { argument = ArgumentMapping.of(parameter.getType(), greedy, nativeTypes); }
                catch (CommandCompilationException failure) { throw invalid(owner, method, format, failure.getMessage()); }
            }
            current = child(current, name, argument, owner, method, format);
        }
        for (String name : parameters.keySet()) {
            if (!names.contains(name)) throw invalid(owner, method, format, "@CmdParam " + name + " is absent from format");
        }
        if (current.method != null) throw invalid(owner, method, format, "duplicate path with " + current.method.getName());
        current.method = method;
        current.format = format;
    }

    private Spec child(Spec parent, String name, ArgumentMapping argument, Class<?> owner, Method method, String format) {
        if (argument != null) {
            for (Spec sibling : parent.children.values()) {
                if (sibling.argument != null && (!sibling.name.equals(name)
                        || !sibling.argument.signature().equals(argument.signature()))) {
                    throw invalid(owner, method, format, "competing parameter with " + sibling.firstMethod.getName());
                }
            }
        }
        Spec existing = parent.children.get(name);
        if (existing != null) {
            if ((existing.argument == null) != (argument == null)) throw invalid(owner, method, format, "literal and parameter have the same node name");
            return existing;
        }
        Spec result = new Spec(name, argument, method);
        parent.children.put(name, result);
        return result;
    }

    private static Map<String, Parameter> parameters(Method method) {
        Map<String, Parameter> result = new LinkedHashMap<>();
        for (Parameter parameter : method.getParameters()) {
            CmdParam annotation = parameter.getAnnotation(CmdParam.class);
            if (annotation != null) result.put(annotation.value(), parameter);
        }
        return result;
    }

    private CommandNode<CommandSourceStack> build(Spec spec, Invocation invocation,
            Command<CommandSourceStack> help, SuggestionBridge suggestions, List<Spec> ancestors) {
        List<Spec> path = new ArrayList<>(ancestors);
        if (spec.argument != null) path.add(spec);
        ArgumentBuilder<CommandSourceStack, ?> builder = spec.argument == null
                ? LiteralArgumentBuilder.<CommandSourceStack>literal(spec.name)
                : RequiredArgumentBuilder.<CommandSourceStack, Object>argument(spec.name, cast(spec.argument.type()))
                        .suggests(suggestions.provider(spec.firstMethod, spec.name, spec.argument.type()));
        if (spec.method != null) builder.executes(new MappingCommand(spec.method, path, null, invocation));
        for (Spec child : spec.children.values()) {
            builder.then(build(child, invocation, help, suggestions, path));
            if (child.argument != null && child.argument.greedy()) {
                if (spec.method != null) throw invalid(spec.method.getDeclaringClass(), child.firstMethod,
                        child.format, "duplicate executable path for empty greedy arguments with " + spec.method.getName());
                List<Spec> greedyPath = new ArrayList<>(path);
                greedyPath.add(child);
                builder.executes(new MappingCommand(child.method, greedyPath, child.name, invocation));
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static com.mojang.brigadier.arguments.ArgumentType<Object> cast(com.mojang.brigadier.arguments.ArgumentType<?> type) {
        return (com.mojang.brigadier.arguments.ArgumentType<Object>) type;
    }

    private static int invoke(CommandContext<CommandSourceStack> context, Method method, List<Spec> path,
            String empty, Invocation invocation) throws CommandSyntaxException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Spec spec : path) values.put(spec.name, spec.argument.value(context, spec.name, spec.name.equals(empty)));
        return invocation.run(context, method, values);
    }

    private static void appendSignature(Spec spec, StringBuilder out) {
        out.append('(').append(spec.name).append(':').append(spec.argument == null ? "literal" : spec.argument.signature());
        if (spec.method != null) {
            CmdMapping mapping = spec.method.getAnnotation(CmdMapping.class);
            out.append("|exec:").append(mapping.permission()).append(':').append(mapping.requireOp());
        }
        spec.children.values().forEach(child -> appendSignature(child, out));
        out.append(')');
    }

    private static CommandCompilationException invalid(Class<?> owner, Method method, String format, String reason) {
        return new CommandCompilationException(owner.getName() + "#" + method.getName() + " [" + format + "]: " + reason);
    }

    record MappingCommand(Method method, List<Spec> path, String empty, Invocation invocation)
            implements Command<CommandSourceStack> {
        @Override public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            return invoke(context, method, path, empty, invocation);
        }
    }

    private static final class Spec {
        final String name;
        final ArgumentMapping argument;
        final Method firstMethod;
        final Map<String, Spec> children = new LinkedHashMap<>();
        Method method;
        String format;
        Spec(String name, ArgumentMapping argument, Method firstMethod) {
            this.name = name; this.argument = argument; this.firstMethod = firstMethod;
        }
    }
}
