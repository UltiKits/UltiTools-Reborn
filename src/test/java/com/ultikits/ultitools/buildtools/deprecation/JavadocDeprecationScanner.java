package com.ultikits.ultitools.buildtools.deprecation;

import com.sun.source.doctree.DeprecatedTree;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.UnknownBlockTagTree;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Build-only declaration discovery. The full JDK owns Java syntax; documentation is optional.
 * Only {@link JavacTask#parse()} and DocTrees are used, with processing disabled. Reflection
 * inspects already compiled declarations without initialization, invocation or field reads.
 * Neither this tool nor its compiler API dependencies belong in the runtime plugin artifact.
 */
public final class JavadocDeprecationScanner {
    private final ClassLoader classLoader;
    private final Supplier<JavaCompiler> compilerSupplier;

    public JavadocDeprecationScanner(ClassLoader classLoader) {
        this(classLoader, ToolProvider::getSystemJavaCompiler);
    }

    JavadocDeprecationScanner(ClassLoader classLoader, Supplier<JavaCompiler> compilerSupplier) {
        this.classLoader = classLoader;
        this.compilerSupplier = compilerSupplier;
    }

    public List<DeprecationEntry> scan(Path srcRoot) throws IOException {
        JavaCompiler compiler = compilerSupplier.get();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK 21 compiler/parser is required to scan " + srcRoot);
        }
        List<Path> files;
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        List<DeprecationEntry> entries = new ArrayList<>();
        for (Path file : files) {
            parseFile(compiler, file, entries);
        }
        return entries;
    }

    private void parseFile(JavaCompiler compiler, Path file, List<DeprecationEntry> entries) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Collections.singletonList("-proc:none"), null,
                    manager.getJavaFileObjects(file.toFile()));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalStateException("Malformed Java source " + file + ":"
                            + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + ": "
                            + diagnostic.getMessage(java.util.Locale.ROOT));
                }
            }
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : units) {
                Source source = new Source(file, unit, docs);
                TreePath root = new TreePath(unit);
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree) {
                        source.visitClass(new TreePath(root, declaration), null, entries);
                    }
                }
            }
        }
    }

    /** One compilation unit supplies imports; only named member declarations are traversed. */
    private final class Source {
        private final Path file;
        private final CompilationUnitTree unit;
        private final DocTrees docs;
        private final String packageName;
        private final Map<String, Set<String>> imports = new LinkedHashMap<>();
        private final List<String> onDemand = new ArrayList<>();

        Source(Path file, CompilationUnitTree unit, DocTrees docs) {
            this.file = file;
            this.unit = unit;
            this.docs = docs;
            this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            onDemand.add("java.lang");
            for (ImportTree imp : unit.getImports()) {
                // Static imports can name member types too; only class loading is attempted.
                String name = imp.getQualifiedIdentifier().toString();
                if (name.endsWith(".*")) {
                    onDemand.add(name.substring(0, name.length() - 2));
                } else {
                    imports.computeIfAbsent(name.substring(name.lastIndexOf('.') + 1), k -> new LinkedHashSet<>())
                            .add(name);
                }
            }
        }

        void visitClass(TreePath path, Owner parent, List<DeprecationEntry> entries) {
            ClassTree tree = (ClassTree) path.getLeaf();
            if (tree.getSimpleName().length() == 0) {
                return;
            }
            String binary = parent == null ? qualify(tree.getSimpleName().toString())
                    : parent.binary + "$" + tree.getSimpleName();
            Owner owner = new Owner(binary, parent, tree.getTypeParameters());
            Scope scope = owner.scope;
            addEntry(path, tree.getModifiers().getAnnotations(), owner, scope, entries);
            for (Tree member : tree.getMembers()) {
                TreePath memberPath = new TreePath(path, member);
                if (member instanceof ClassTree) {
                    visitClass(memberPath, owner, entries);
                } else if (member instanceof MethodTree) {
                    MethodTree method = (MethodTree) member;
                    addEntry(memberPath, method.getModifiers().getAnnotations(), owner,
                            new Scope(scope, method.getTypeParameters(), owner), entries);
                } else if (member instanceof VariableTree) {
                    addEntry(memberPath, ((VariableTree) member).getModifiers().getAnnotations(), owner, scope, entries);
                }
                // Method bodies, initializer expressions/blocks, local/anonymous classes and
                // local variables are not API declarations and are deliberately not visited.
            }
        }

        private void addEntry(TreePath path, List<? extends AnnotationTree> annotations, Owner owner,
                Scope scope, List<DeprecationEntry> entries) {
            Tree declaration = path.getLeaf();
            String context = file + ":" + unit.getLineMap().getLineNumber(
                    docs.getSourcePositions().getStartPosition(unit, declaration)) + " "
                    + owner.binary + " " + declaration.getKind() + " " + declarationName(declaration);
            try {
                boolean deprecated = false;
                for (AnnotationTree annotation : annotations) {
                    Set<String> types = resolve(annotation.getAnnotationType(), owner, scope, new HashSet<>());
                    if (types.contains("java.lang.Deprecated")) {
                        if (types.size() != 1) {
                            throw new IllegalStateException("Ambiguous annotation identity: " + types);
                        }
                        deprecated = true;
                    }
                }
                if (!deprecated) {
                    return;
                }
                Class<?> compiledOwner = load(owner.binary);
                RegistryKey key;
                DeprecationEntry.Kind kind;
                AnnotatedElement reflected;
                if (declaration instanceof ClassTree) {
                    key = RegistryKey.forClass(compiledOwner.getName());
                    kind = DeprecationEntry.Kind.CLASS;
                    reflected = compiledOwner;
                } else if (declaration instanceof VariableTree) {
                    VariableTree variable = (VariableTree) declaration;
                    Set<String> types = resolve(variable.getType(), owner, scope, new HashSet<>());
                    List<Field> candidates = Arrays.asList(compiledOwner.getDeclaredFields());
                    List<Field> matches = candidates.stream()
                            .filter(f -> f.getName().contentEquals(variable.getName())
                                    && types.contains(typeName(f.getType())))
                            .collect(Collectors.toList());
                    if (matches.size() != 1) {
                        throw new IllegalStateException((matches.isEmpty() ? "No reflected field matched " : "Ambiguous field match ")
                                + variable.getName() + " " + types + "; candidates: " + candidates + "; matches: " + matches);
                    }
                    Field field = matches.get(0);
                    key = RegistryKey.forField(compiledOwner.getName(), field.getName());
                    kind = DeprecationEntry.Kind.FIELD;
                    reflected = field;
                } else {
                    MethodTree method = (MethodTree) declaration;
                    boolean constructor = method.getReturnType() == null;
                    List<Set<String>> parameters = new ArrayList<>();
                    for (VariableTree parameter : method.getParameters()) {
                        parameters.add(resolve(parameter.getType(), owner, scope, new HashSet<>()));
                    }
                    List<Executable> candidates = new ArrayList<>();
                    if (constructor) {
                        candidates.addAll(Arrays.asList(compiledOwner.getDeclaredConstructors()));
                    } else {
                        for (Method candidate : compiledOwner.getDeclaredMethods()) {
                            if (candidate.getName().contentEquals(method.getName()) && !candidate.isBridge()
                                    && !candidate.isSynthetic()) {
                                candidates.add(candidate);
                            }
                        }
                    }
                    Executable match = uniqueMatch(candidates, parameters, compiledOwner, constructor);
                    key = RegistryKey.forMember(compiledOwner.getName(), constructor
                                    ? compiledOwner.getSimpleName() : method.getName().toString(),
                            Arrays.stream(match.getParameterTypes()).map(JavadocDeprecationScanner::typeName)
                                    .collect(Collectors.toList()));
                    kind = constructor ? DeprecationEntry.Kind.CONSTRUCTOR : DeprecationEntry.Kind.METHOD;
                    reflected = match;
                }
                if (!reflected.isAnnotationPresent(Deprecated.class)) {
                    throw new IllegalStateException("Compiled declaration lacks java.lang.Deprecated: " + key);
                }
                String since = null;
                boolean forRemoval = false;
                for (AnnotationTree annotation : annotations) {
                    if (!resolve(annotation.getAnnotationType(), owner, scope, new HashSet<>())
                            .contains("java.lang.Deprecated")) {
                        continue;
                    }
                    for (com.sun.source.tree.ExpressionTree argument : annotation.getArguments()) {
                        if (!(argument instanceof com.sun.source.tree.AssignmentTree)) {
                            throw new IllegalStateException("Unsupported Deprecated argument: " + argument);
                        }
                        com.sun.source.tree.AssignmentTree assignment = (com.sun.source.tree.AssignmentTree) argument;
                        if (!(assignment.getExpression() instanceof com.sun.source.tree.LiteralTree)) {
                            throw new IllegalStateException("Deprecated metadata must be literal: " + argument);
                        }
                        Object value = ((com.sun.source.tree.LiteralTree) assignment.getExpression()).getValue();
                        if (assignment.getVariable().toString().equals("since")) {
                            since = (String) value;
                        } else if (assignment.getVariable().toString().equals("forRemoval")) {
                            forRemoval = (Boolean) value;
                        }
                    }
                }
                String paragraph = "";
                String removeIn = null;
                DocCommentTree doc = docs.getDocCommentTree(path);
                if (doc != null) {
                    for (DocTree tag : doc.getBlockTags()) {
                        if (tag instanceof DeprecatedTree) {
                            paragraph = joinDoc(((DeprecatedTree) tag).getBody());
                        } else if (tag instanceof UnknownBlockTagTree
                                && ((UnknownBlockTagTree) tag).getTagName().equals("removeIn")) {
                            String text = joinDoc(((UnknownBlockTagTree) tag).getContent()).trim();
                            removeIn = text.isEmpty() ? null : text.split("\\s+")[0];
                        }
                    }
                }
                entries.add(DeprecationEntry.builder().key(key).kind(kind).since(since).forRemoval(forRemoval)
                        .removeIn(removeIn).replacement(DeprecatedParagraphExtractor.extractReplacementText(paragraph))
                        .status(forRemoval ? DeprecationEntry.Status.ANNOUNCED : DeprecationEntry.Status.DEPRECATED)
                        .build());
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                throw new IllegalStateException("Cannot map declaration " + context + ": " + e.getMessage(), e);
            }
        }

        private Executable uniqueMatch(List<Executable> candidates, List<Set<String>> parameters,
                Class<?> owner, boolean constructor) {
            List<Executable> matches = new ArrayList<>();
            int hidden = constructor && owner.isEnum() ? 2
                    : constructor && owner.isMemberClass() && !Modifier.isStatic(owner.getModifiers()) ? 1 : 0;
            for (Executable candidate : candidates) {
                Class<?>[] types = candidate.getParameterTypes();
                if (types.length != parameters.size() + hidden) {
                    continue;
                }
                boolean same = true;
                for (int i = 0; i < parameters.size(); i++) {
                    if (!parameters.get(i).contains(typeName(types[i + hidden]))) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    matches.add(candidate);
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException((matches.isEmpty() ? "No reflected member matched " : "Ambiguous match ")
                        + parameters + "; candidates: " + candidates + "; matches: " + matches);
            }
            return matches.get(0);
        }

        private Set<String> resolve(Tree tree, Owner owner, Scope scope, Set<Bound> resolving) {
            if (tree instanceof AnnotatedTypeTree) {
                return resolve(((AnnotatedTypeTree) tree).getUnderlyingType(), owner, scope, resolving);
            }
            if (tree instanceof ArrayTypeTree) {
                Set<String> result = new LinkedHashSet<>();
                for (String type : resolve(((ArrayTypeTree) tree).getType(), owner, scope, resolving)) {
                    result.add(type + "[]");
                }
                return result;
            }
            if (tree instanceof ParameterizedTypeTree) {
                return resolve(((ParameterizedTypeTree) tree).getType(), owner, scope, resolving);
            }
            String name = typeSpelling(tree);
            if (tree.getKind() == Tree.Kind.PRIMITIVE_TYPE) {
                return Collections.singleton(name);
            }
            Bound bound = scope == null ? null : scope.find(name);
            if (bound != null) {
                if (!resolving.add(bound)) {
                    throw new IllegalStateException("Cyclic type-variable erasure: " + name);
                }
                Set<String> types = bound.tree == null ? Collections.singleton("java.lang.Object")
                        : resolve(bound.tree, bound.scope.owner, bound.scope, resolving);
                resolving.remove(bound);
                return types;
            }
            return resolveName(name, owner);
        }

        private Set<String> resolveName(String name, Owner owner) {
            int dot = name.indexOf('.');
            if (dot >= 0) {
                Set<String> result = loadableNames(name);
                for (String prefix : resolveName(name.substring(0, dot), owner)) {
                    result.addAll(loadableNames(prefix + "." + name.substring(dot + 1)));
                }
                return result;
            }
            for (Owner lexical = owner; lexical != null; lexical = lexical.parent) {
                Set<String> found = new LinkedHashSet<>();
                if (lexical.binary.substring(lexical.binary.lastIndexOf('$') + 1)
                        .equals(name) || lexical.binary.equals(qualify(name))) {
                    found.addAll(loadableNames(lexical.binary));
                }
                found.addAll(loadableNames(lexical.binary + "$" + name));
                if (!found.isEmpty()) {
                    return found;
                }
            }
            if (imports.containsKey(name)) {
                Set<String> found = new LinkedHashSet<>();
                for (String imported : imports.get(name)) {
                    found.addAll(loadableNames(imported));
                }
                return found;
            }
            Set<String> local = loadableNames(qualify(name));
            if (!local.isEmpty()) {
                return local;
            }
            Set<String> found = new LinkedHashSet<>();
            for (String prefix : onDemand) {
                found.addAll(loadableNames(prefix + "." + name));
            }
            return found;
        }

        private Set<String> loadableNames(String spelling) {
            Set<String> names = new LinkedHashSet<>();
            String binary = spelling;
            while (true) {
                try {
                    names.add(load(binary).getName());
                    return names;
                } catch (ClassNotFoundException ignored) {
                    // Only a missing candidate is expected. Linkage failures are never absence.
                }
                int dot = binary.lastIndexOf('.');
                if (dot < 0) {
                    return names;
                }
                binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
            }
        }

        private String qualify(String simple) {
            return packageName.isEmpty() ? simple : packageName + "." + simple;
        }
    }

    private Class<?> load(String binaryName) throws ClassNotFoundException {
        // Build-only names come from parsed repository declarations/imports, never invocation.
        // nosemgrep: java.lang.security.audit.unsafe-reflection.unsafe-reflection
        return Class.forName(binaryName, false, classLoader);
    }

    private static String typeSpelling(Tree tree) {
        if (tree instanceof MemberSelectTree) {
            MemberSelectTree member = (MemberSelectTree) tree;
            return typeSpelling(member.getExpression()) + "." + member.getIdentifier();
        }
        if (tree instanceof ParameterizedTypeTree) {
            return typeSpelling(((ParameterizedTypeTree) tree).getType());
        }
        return tree.toString();
    }

    private static String declarationName(Tree tree) {
        if (tree instanceof ClassTree) {
            return ((ClassTree) tree).getSimpleName().toString();
        }
        if (tree instanceof MethodTree) {
            MethodTree method = (MethodTree) tree;
            return method.getName() + method.getParameters().toString();
        }
        return ((VariableTree) tree).getName().toString();
    }

    private static String joinDoc(List<? extends DocTree> trees) {
        return trees.stream().map(Object::toString).collect(Collectors.joining()).replace('\n', ' ').replace('\r', ' ');
    }

    private static String typeName(Class<?> type) {
        return type.isArray() ? typeName(type.getComponentType()) + "[]" : type.getName();
    }

    private static final class Owner {
        final String binary;
        final Owner parent;
        final Scope scope;

        Owner(String binary, Owner parent, List<? extends TypeParameterTree> parameters) {
            this.binary = binary;
            this.parent = parent;
            this.scope = new Scope(parent == null ? null : parent.scope, parameters, this);
        }
    }

    /** Each bound keeps its declaring lexical scope, so method shadowing cannot rewrite it. */
    private static final class Bound {
        final Tree tree;
        final Scope scope;

        Bound(Tree tree, Scope scope) {
            this.tree = tree;
            this.scope = scope;
        }
    }

    private static final class Scope {
        final Scope parent;
        final Owner owner;
        final Map<String, Bound> bounds = new LinkedHashMap<>();

        Scope(Scope parent, List<? extends TypeParameterTree> parameters, Owner owner) {
            this.parent = parent;
            this.owner = owner;
            for (TypeParameterTree parameter : parameters) {
                bounds.put(parameter.getName().toString(), new Bound(
                        parameter.getBounds().isEmpty() ? null : parameter.getBounds().get(0), this));
            }
        }

        Bound find(String name) {
            Bound bound = bounds.get(name);
            return bound != null ? bound : parent == null ? null : parent.find(name);
        }
    }
}
