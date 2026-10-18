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
import java.util.HashMap;
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
 * Deprecated presence and its {@code since}/{@code forRemoval} values come from the class file
 * ({@link CompiledDeprecationReader}), never from reflective annotation access, which would
 * resolve co-located annotation values and so initialize enum types they name. Reading the
 * compiled constants also accepts any javac compile-time constant expression without evaluating it.
 * Neither this tool nor its compiler API dependencies belong in the runtime plugin artifact.
 */
public final class JavadocDeprecationScanner {
    private final ClassLoader classLoader;
    private final Supplier<JavaCompiler> compilerSupplier;
    private final MemberTypeScope memberTypes = new MemberTypeScope(this::load);

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
        private final Map<String, Set<String>> staticImports = new LinkedHashMap<>();
        private final List<String> onDemand = new ArrayList<>();
        private final List<String> staticOnDemand = new ArrayList<>();
        private final Map<Class<?>, CompiledDeprecationReader> compiledClasses = new HashMap<>();

        /**
         * A type import names a canonical type, so only declared member types are reachable through
         * it (JLS 7.5.1, 7.5.2). A static import names a canonical type and any of its static members,
         * so it also reaches member types the type inherits (JLS 7.5.3, 7.5.4). Only class loading
         * is attempted.
         */
        Source(Path file, CompilationUnitTree unit, DocTrees docs) {
            this.file = file;
            this.unit = unit;
            this.docs = docs;
            this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            onDemand.add("java.lang");
            for (ImportTree imp : unit.getImports()) {
                String name = imp.getQualifiedIdentifier().toString();
                if (name.endsWith(".*")) {
                    (imp.isStatic() ? staticOnDemand : onDemand).add(name.substring(0, name.length() - 2));
                } else {
                    (imp.isStatic() ? staticImports : imports)
                            .computeIfAbsent(name.substring(name.lastIndexOf('.') + 1), k -> new LinkedHashSet<>())
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
                if (!annotatedDeprecated(annotations, owner, scope)) {
                    return;
                }
                Class<?> compiledOwner = load(owner.binary);
                CompiledDeprecationReader compiled = compiledClass(compiledOwner);
                Mapped mapped;
                if (declaration instanceof ClassTree) {
                    mapped = new Mapped(RegistryKey.forClass(compiledOwner.getName()), DeprecationEntry.Kind.CLASS,
                            compiled.forClass());
                } else if (declaration instanceof VariableTree) {
                    mapped = mapField((VariableTree) declaration, compiledOwner, compiled, owner, scope);
                } else {
                    mapped = mapExecutable((MethodTree) declaration, compiledOwner, compiled, owner, scope);
                }
                if (mapped.metadata == null) {
                    throw new IllegalStateException("Compiled declaration lacks java.lang.Deprecated: " + mapped.key);
                }
                entries.add(entry(path, mapped));
            } catch (ReflectiveOperationException | LinkageError | IOException | RuntimeException e) {
                throw new IllegalStateException("Cannot map declaration " + context + ": " + e.getMessage(), e);
            }
        }

        private boolean annotatedDeprecated(List<? extends AnnotationTree> annotations, Owner owner, Scope scope) {
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
            return deprecated;
        }

        private CompiledDeprecationReader compiledClass(Class<?> compiledOwner) throws IOException {
            CompiledDeprecationReader compiled = compiledClasses.get(compiledOwner);
            if (compiled == null) {
                compiled = CompiledDeprecationReader.read(classLoader, compiledOwner);
                compiledClasses.put(compiledOwner, compiled);
            }
            return compiled;
        }

        private Mapped mapField(VariableTree variable, Class<?> compiledOwner, CompiledDeprecationReader compiled,
                Owner owner, Scope scope) {
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
            return new Mapped(RegistryKey.forField(compiledOwner.getName(), field.getName()),
                    DeprecationEntry.Kind.FIELD, compiled.forField(field));
        }

        private Mapped mapExecutable(MethodTree method, Class<?> compiledOwner, CompiledDeprecationReader compiled,
                Owner owner, Scope scope) {
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
            RegistryKey key = RegistryKey.forMember(compiledOwner.getName(), constructor
                            ? compiledOwner.getSimpleName() : method.getName().toString(),
                    Arrays.stream(match.getParameterTypes()).map(JavadocDeprecationScanner::typeName)
                            .collect(Collectors.toList()));
            return new Mapped(key, constructor ? DeprecationEntry.Kind.CONSTRUCTOR : DeprecationEntry.Kind.METHOD,
                    compiled.forExecutable(match));
        }

        private DeprecationEntry entry(TreePath path, Mapped mapped) {
            String paragraph = "";
            String removeIn = null;
            DocCommentTree doc = docs.getDocCommentTree(path);
            if (doc != null) {
                for (DocTree tag : doc.getBlockTags()) {
                    if (tag instanceof DeprecatedTree) {
                        paragraph = joinDoc(((DeprecatedTree) tag).getBody());
                    } else if (tag instanceof UnknownBlockTagTree
                            && "removeIn".equals(((UnknownBlockTagTree) tag).getTagName())) {
                        String text = joinDoc(((UnknownBlockTagTree) tag).getContent()).trim();
                        removeIn = text.isEmpty() ? null : text.split("\\s+")[0];
                    }
                }
            }
            boolean forRemoval = mapped.metadata.forRemoval;
            return DeprecationEntry.builder().key(mapped.key).kind(mapped.kind).since(mapped.metadata.since)
                    .forRemoval(forRemoval).removeIn(removeIn)
                    .replacement(DeprecatedParagraphExtractor.extractReplacementText(paragraph))
                    .status(forRemoval ? DeprecationEntry.Status.ANNOUNCED : DeprecationEntry.Status.DEPRECATED)
                    .build();
        }

        private Executable uniqueMatch(List<Executable> candidates, List<Set<String>> parameters,
                Class<?> owner, boolean constructor) {
            List<Executable> matches = new ArrayList<>();
            int hidden = hiddenParameters(owner, constructor);
            for (Executable candidate : candidates) {
                if (parametersMatch(candidate.getParameterTypes(), parameters, hidden)) {
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
                return arrayOf(resolve(((ArrayTypeTree) tree).getType(), owner, scope, resolving));
            }
            if (tree instanceof ParameterizedTypeTree) {
                return resolve(((ParameterizedTypeTree) tree).getType(), owner, scope, resolving);
            }
            String name = typeSpelling(tree);
            if (tree.getKind() == Tree.Kind.PRIMITIVE_TYPE) {
                return Collections.singleton(name);
            }
            Bound bound = scope == null ? null : scope.find(name);
            return bound != null ? resolveBound(bound, name, resolving) : resolveName(name, owner);
        }

        private Set<String> resolveBound(Bound bound, String name, Set<Bound> resolving) {
            if (!resolving.add(bound)) {
                throw new IllegalStateException("Cyclic type-variable erasure: " + name);
            }
            Set<String> types = bound.tree == null ? Collections.singleton("java.lang.Object")
                    : resolve(bound.tree, bound.scope.owner, bound.scope, resolving);
            resolving.remove(bound);
            return types;
        }

        private Set<String> resolveName(String name, Owner owner) {
            if (name.indexOf('.') >= 0) {
                return resolveQualified(name, owner);
            }
            Set<String> lexical = resolveLexical(name, owner);
            if (!lexical.isEmpty()) {
                return lexical;
            }
            if (imports.containsKey(name) || staticImports.containsKey(name)) {
                return resolveSingleImports(name);
            }
            Set<String> local = loadableNames(qualify(name));
            return local.isEmpty() ? resolveOnDemand(name) : local;
        }

        private Set<String> resolveSingleImports(String name) {
            Set<String> found = new LinkedHashSet<>();
            for (String imported : imports.getOrDefault(name, Collections.emptySet())) {
                found.addAll(loadableNames(imported));
            }
            for (String imported : staticImports.getOrDefault(name, Collections.emptySet())) {
                found.addAll(memberTypesOf(imported.substring(0, imported.lastIndexOf('.')), name));
            }
            return found;
        }

        private Set<String> resolveOnDemand(String name) {
            Set<String> found = new LinkedHashSet<>();
            for (String prefix : onDemand) {
                found.addAll(loadableNames(prefix + "." + name));
            }
            for (String prefix : staticOnDemand) {
                found.addAll(memberTypesOf(prefix, name));
            }
            return found;
        }

        /**
         * A qualified type name Q.Id names a member type of Q, declared or inherited (JLS 6.5.5.2,
         * 8.5): the qualifier is resolved first, then {@link MemberTypeScope} looks up Id in each
         * type it names, so a declared member type still hides an inherited one and two inherited
         * ones stay ambiguous. A qualifier that is a package instead is covered by loading the
         * whole name as spelled.
         */
        private Set<String> resolveQualified(String name, Owner owner) {
            int dot = name.lastIndexOf('.');
            String member = name.substring(dot + 1);
            Set<String> result = loadableNames(name);
            for (String qualifier : resolveName(name.substring(0, dot), owner)) {
                result.addAll(memberTypes.find(qualifier, member));
            }
            return result;
        }

        /** Member types named {@code name}, declared or inherited, of the canonically named type. */
        private Set<String> memberTypesOf(String canonical, String name) {
            Set<String> found = new LinkedHashSet<>();
            for (String type : loadableNames(canonical)) {
                found.addAll(memberTypes.find(type, name));
            }
            return found;
        }

        /**
         * The innermost enclosing owner that has the simple name as a member type, declared or
         * inherited from a supertype (JLS 8.5, 6.4.1; see {@link MemberTypeScope}), or that is
         * itself so named, wins.
         */
        private Set<String> resolveLexical(String name, Owner owner) {
            for (Owner lexical = owner; lexical != null; lexical = lexical.parent) {
                Set<String> found = memberTypes.find(lexical.binary, name);
                if (found.isEmpty() && namesItself(lexical.binary, name, qualify(name))) {
                    found = loadableNames(lexical.binary);
                }
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return Collections.emptySet();
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

    /** Whether the owner's own simple name, or its top-level qualified name, is the spelled name. */
    private static boolean namesItself(String binary, String name, String qualified) {
        return binary.substring(binary.lastIndexOf('$') + 1).equals(name) || binary.equals(qualified);
    }

    private static int hiddenParameters(Class<?> owner, boolean constructor) {
        if (!constructor) {
            return 0;
        }
        if (owner.isEnum()) {
            return 2;
        }
        return owner.isMemberClass() && !Modifier.isStatic(owner.getModifiers()) ? 1 : 0;
    }

    private static boolean parametersMatch(Class<?>[] types, List<Set<String>> parameters, int hidden) {
        if (types.length != parameters.size() + hidden) {
            return false;
        }
        for (int i = 0; i < parameters.size(); i++) {
            if (!parameters.get(i).contains(typeName(types[i + hidden]))) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> arrayOf(Set<String> components) {
        Set<String> result = new LinkedHashSet<>();
        for (String type : components) {
            result.add(type + "[]");
        }
        return result;
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

    /** One source declaration matched to its compiled counterpart. */
    private static final class Mapped {
        final RegistryKey key;
        final DeprecationEntry.Kind kind;
        final CompiledDeprecationReader.Metadata metadata;

        Mapped(RegistryKey key, DeprecationEntry.Kind kind, CompiledDeprecationReader.Metadata metadata) {
            this.key = key;
            this.kind = kind;
            this.metadata = metadata;
        }
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
