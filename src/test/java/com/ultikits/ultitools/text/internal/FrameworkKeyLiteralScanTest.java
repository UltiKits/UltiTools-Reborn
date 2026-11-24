package com.ultikits.ultitools.text.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.ultikits.ultitools.text.MessageKey;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.VariableElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class FrameworkKeyLiteralScanTest {
    @Test
    void allFrameworkReferences_existInBothCatalogues() throws Exception {
        Set<MessageKey> keys = new HashSet<>();
        Path root = Path.of("src/main/java");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                // nosemgrep: java.lang.security.audit.path-traversal.path-traversal
                keys.addAll(scan(path.toString(), Files.readString(path))); // Fixed repository source root.
            }
        }
        assertThat(keys).as("positive control includes literals in the unmodified UI contracts")
                .contains(FrameworkKeys.UI_OK, FrameworkKeys.UI_YES, FrameworkKeys.UI_NO, FrameworkKeys.UI_SUBMIT);
        FrameworkKeysCatalogueTest.assertKeysPresent(keys);
    }

    @Test
    void positiveControl_detectsAMissingLiteralOutsideTheConstantsClass() throws Exception {
        Set<MessageKey> keys = scan("Outside.java", """
                class Outside {
                    Object message = MessageKey.of("ultitools", "test.outside_missing");
                }
                """);
        assertThat(keys).containsExactly(MessageKey.of("ultitools", "test.outside_missing"));
        assertThatThrownBy(() -> FrameworkKeysCatalogueTest.assertKeysPresent(keys))
                .isInstanceOf(AssertionError.class).hasMessageContaining("ultitools:test.outside_missing");
    }

    @Test
    void runtimePaths_areRejectedWithSourceLocation() {
        assertThatThrownBy(() -> scan("Dynamic.java", """
                class Dynamic {
                    Object message(String suffix) {
                        return MessageKey.of("ultitools", "ui." + suffix);
                    }
                }
                """))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Dynamic.java:3", "non-constant framework key");
    }

    @Test
    void parser_acceptsConstantPathsAndIgnoresCommentsStringsAndOtherNamespaces() throws Exception {
        assertThat(scan("Example.java", """
                class Example {
                    static final String PATH = "ui." + "ok";
                    // MessageKey.of("ultitools", "comment.missing");
                    String example = "MessageKey.of(\\\"ultitools\\\", \\\"string.missing\\\")";
                    Object actual = MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, PATH);
                    Object foreign = MessageKey.of("sample", computed());
                }
                """))
                .containsExactly(FrameworkKeys.UI_OK);
    }

    @Test
    void constantNamespaces_areResolvedBeforeValidatingTheirKeys() throws Exception {
        assertThat(scan("Namespace.java", """
                class Namespace {
                    static final String NS = "ulti" + "tools";
                    Object message = MessageKey.of(NS, "ui.ok");
                }
                """))
                .containsExactly(FrameworkKeys.UI_OK);
        assertThatThrownBy(() -> scan("Dynamic.java", """
                class Dynamic {
                    static final String NS = "ultitools";
                    Object message(String path) { return MessageKey.of(NS, path); }
                }
                """))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Dynamic.java:3", "non-constant framework key");
    }

    @Test
    void parameterShadowingAConstant_isStillARuntimeKey() {
        assertThatThrownBy(() -> scan("Shadow.java", """
                class Shadow {
                    static final String PATH = "ui.ok";
                    Object message(String PATH) { return MessageKey.of("ultitools", PATH); }
                }
                """))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Shadow.java:3", "non-constant framework key");
    }

    @Test
    void finalButRuntimeInitializedField_isNotACompileTimeConstant() {
        assertThatThrownBy(() -> scan("Runtime.java", """
                class Runtime {
                    static final String PATH = System.getProperty("ui.key");
                    Object message = MessageKey.of("ultitools", PATH);
                }
                """))
                .isInstanceOf(AssertionError.class).hasMessageContaining("Runtime.java:3", "non-constant framework key");
    }

    private static Set<MessageKey> scan(String name, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("source guard requires the build JDK compiler").isNotNull();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Scan.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, new DiagnosticCollector<>(),
                    List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            List<MethodInvocationTree> calls = keyCalls(unit);
            if (calls.stream().anyMatch(call -> needsSymbols(call.getArguments().getFirst())
                    || needsSymbols(call.getArguments().get(1)))) {
                // Maven already compiles real sources. Here javac supplies exact constant-value and scope semantics;
                // fixtures deliberately leave unrelated symbols unresolved and annotation processing stays disabled.
                task.analyze();
            }
            Set<MessageKey> keys = new HashSet<>();
            Trees trees = Trees.instance(task);
            for (MethodInvocationTree call : calls) {
                String namespace = namespace(call.getArguments().getFirst(), unit, trees);
                if (!MessageKey.FRAMEWORK_NAMESPACE.equals(namespace)) {
                    continue;
                }
                String path = constant(call.getArguments().get(1), unit, trees);
                long position = trees.getSourcePositions().getStartPosition(unit, call);
                assertThat(path).as("%s:%s: non-constant framework key: %s",
                        name, unit.getLineMap().getLineNumber(position), call).isNotNull();
                keys.add(MessageKey.of(MessageKey.FRAMEWORK_NAMESPACE, path));
            }
            return keys;
        }
    }

    private static List<MethodInvocationTree> keyCalls(CompilationUnitTree unit) {
        List<MethodInvocationTree> calls = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                if (isKeyFactoryCall(call)) {
                    calls.add(call);
                }
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(unit, null);
        return calls;
    }

    private static boolean isKeyFactoryCall(MethodInvocationTree call) {
        String method = call.getMethodSelect().toString();
        return (method.equals("MessageKey.of") || method.endsWith(".MessageKey.of"))
                && call.getArguments().size() == 2;
    }

    private static boolean needsSymbols(ExpressionTree tree) {
        return !(tree instanceof LiteralTree) && !isFrameworkNamespace(tree);
    }

    private static boolean isFrameworkNamespace(ExpressionTree tree) {
        String name = tree.toString();
        return name.equals("MessageKey.FRAMEWORK_NAMESPACE") || name.endsWith(".MessageKey.FRAMEWORK_NAMESPACE");
    }

    private static String namespace(ExpressionTree tree, CompilationUnitTree unit, Trees trees) {
        return isFrameworkNamespace(tree) ? MessageKey.FRAMEWORK_NAMESPACE : constant(tree, unit, trees);
    }

    private static String constant(ExpressionTree tree, CompilationUnitTree unit, Trees trees) {
        if (tree instanceof LiteralTree literal && literal.getValue() instanceof String text) {
            return text;
        }
        if (tree instanceof ParenthesizedTree parenthesized) {
            return constant(parenthesized.getExpression(), unit, trees);
        }
        if (tree instanceof BinaryTree binary && tree.getKind() == Tree.Kind.PLUS) {
            String left = constant(binary.getLeftOperand(), unit, trees);
            String right = constant(binary.getRightOperand(), unit, trees);
            return left == null || right == null ? null : left + right;
        }
        var element = trees.getElement(TreePath.getPath(unit, tree));
        return element instanceof VariableElement variable && variable.getConstantValue() instanceof String value
                ? value : null;
    }
}
