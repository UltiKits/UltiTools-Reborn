package com.ultikits.ultitools.buildtools.deprecation;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans {@code src/main/java} for {@code @Deprecated}-annotated declarations and builds one
 * {@link DeprecationEntry} per site.
 *
 * <p><b>Why this cannot be a bytecode-only or annotation-processor tool</b> (07-RESEARCH.md
 * Priority 2): the {@code @deprecated} javadoc paragraph text is verifiably absent from compiled
 * {@code .class} files, and an annotation processor runs at {@code compile} time, before
 * japicmp's {@code verify}-phase report D-22 requires this scan to cross-check against exists.
 *
 * <p><b>Implementation shape.</b> This scans raw source text rather than a full
 * {@code com.sun.source.util.JavacTask} parse tree - a deliberate implementation-style choice
 * (07-RESEARCH.md leaves Doclet-vs-JavacTask-vs-equivalent open as "not blocked", and
 * 07-CONTEXT.md's own Claude's Discretion list includes the exact extraction mechanics). Source
 * text is masked (comments and string/char literals blanked to spaces, preserving length and
 * newlines) before any brace-depth walk, so a javadoc {@code {@link}}'s braces never get counted
 * as code braces. Once a declaration site is located and its simple parameter-type text is known,
 * {@link Class#getDeclaredMethod}/{@code getDeclaredConstructor}/{@code getDeclaredField}
 * reflection against the already-compiled class (this generator runs at {@code verify}, after
 * {@code compile}) resolves the final fully-qualified parameter types - the one piece source text
 * alone cannot give reliably (generics, imports).
 */
public final class JavadocDeprecationScanner {

    private static final Pattern JAVADOC_COMMENT = Pattern.compile("/\\*\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_OPEN = Pattern.compile(
            "\\b(?:class|interface|enum|@\\s*interface)\\s+(\\w+)");
    private static final Pattern DEPRECATED_ANNOTATION = Pattern.compile(
            "@Deprecated\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern SINCE_ARG = Pattern.compile("since\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FOR_REMOVAL_ARG = Pattern.compile("forRemoval\\s*=\\s*(true|false)");
    private static final Pattern REMOVE_IN_TAG = Pattern.compile(
            "(?m)^\\s*\\*\\s*@removeIn\\s+(\\S+)");
    /**
     * Matches the real {@code @deprecated} javadoc BLOCK tag - anchored to a javadoc line start
     * (optional leading whitespace, a {@code *}, optional whitespace) so a prose mention like
     * {@code see the {@code @deprecated} note} inside the paragraph's own description is never
     * mistaken for the tag itself. Stops at the next block tag or the comment's closing
     * {@code * /}, never bleeding into it.
     */
    private static final Pattern DEPRECATED_TAG_BODY = Pattern.compile(
            "(?m)^[ \\t]*\\*[ \\t]*@deprecated\\b(.*?)(?=\\n[ \\t]*\\*[ \\t]*@\\w|\\n[ \\t]*\\*/|\\z)", Pattern.DOTALL);

    private final ClassLoader classLoader;

    public JavadocDeprecationScanner(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public List<DeprecationEntry> scan(Path srcRoot) throws IOException {
        List<DeprecationEntry> result = new ArrayList<>();
        for (Path file : collectJavaFiles(srcRoot)) {
            result.addAll(scanFile(file));
        }
        return result;
    }

    private List<Path> collectJavaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files);
        return files;
    }

    private List<DeprecationEntry> scanFile(Path file) throws IOException {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String packageName = extractPackageName(source);
        String masked = maskCommentsAndStrings(source);
        List<NestingEvent> nesting = buildNestingEvents(masked);

        List<DeprecationEntry> entries = new ArrayList<>();
        Matcher commentMatcher = JAVADOC_COMMENT.matcher(source);
        while (commentMatcher.find()) {
            String comment = commentMatcher.group();
            if (!comment.contains("@deprecated")) {
                continue;
            }
            List<String> enclosingStack = enclosingClassAt(nesting, commentMatcher.start());
            Declaration decl = parseDeclarationAfter(source, commentMatcher.end());
            if (decl == null || !decl.hasDeprecatedAnnotation) {
                continue;
            }

            String rawParagraph = extractDeprecatedBody(comment);
            String replacement = DeprecatedParagraphExtractor.extractReplacementText(rawParagraph);
            String removeIn = extractRemoveInTag(comment);

            try {
                DeprecationEntry entry = buildEntry(packageName, enclosingStack, decl, decl.since, decl.forRemoval, removeIn, replacement);
                entries.add(entry);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "Failed to resolve reflected member for " + buildBinaryName(packageName, enclosingStack, null)
                                + " (" + decl.kind + " " + decl.name + ") in " + file + ": " + e.getMessage(), e);
            }
        }
        return entries;
    }

    // ---- source text helpers -------------------------------------------------------------

    private static String extractPackageName(String source) {
        Matcher m = PACKAGE_DECL.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Replaces every character inside a {@code //} line comment, a {@code /* *}{@code /} block
     * comment (including javadoc), a string literal, or a char literal with a space, preserving
     * newlines and overall length. This lets a subsequent brace-depth walk find real code braces
     * without a javadoc {@code {@link}}'s braces being counted.
     */
    private static String maskCommentsAndStrings(String source) {
        char[] chars = source.toCharArray();
        char[] masked = chars.clone();
        int n = chars.length;
        int i = 0;
        while (i < n) {
            char c = chars[i];
            if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                int start = i;
                while (i < n && chars[i] != '\n') {
                    i++;
                }
                blank(masked, start, i);
            } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                int start = i;
                i += 2;
                while (i + 1 < n && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                blank(masked, start, i);
            } else if (c == '"') {
                int start = i;
                i++;
                while (i < n && chars[i] != '"') {
                    if (chars[i] == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                blank(masked, start, i);
            } else if (c == '\'') {
                int start = i;
                i++;
                while (i < n && chars[i] != '\'') {
                    if (chars[i] == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i = Math.min(i + 1, n);
                blank(masked, start, i);
            } else {
                i++;
            }
        }
        return new String(masked);
    }

    private static void blank(char[] masked, int from, int to) {
        for (int i = from; i < to; i++) {
            if (masked[i] != '\n') {
                masked[i] = ' ';
            }
        }
    }

    private static final class NestingEvent {
        final int offset;
        final boolean push;
        final String name;

        NestingEvent(int offset, boolean push, String name) {
            this.offset = offset;
            this.push = push;
            this.name = name;
        }
    }

    /**
     * Walks the masked (comment/string-free) source once, tracking brace depth and matching each
     * {@code class}/{@code interface}/{@code enum} keyword to the {@code {} that opens its body,
     * producing an ordered list of push/pop events used by {@link #enclosingClassAt}.
     */
    private static List<NestingEvent> buildNestingEvents(String masked) {
        List<NestingEvent> events = new ArrayList<>();
        List<Integer> depthStack = new ArrayList<>();
        int depth = 0;
        String pendingName = null;

        Matcher classMatcher = CLASS_OPEN.matcher(masked);
        int nextClassMatchStart = classMatcher.find() ? classMatcher.start() : -1;

        for (int i = 0; i < masked.length(); i++) {
            if (i == nextClassMatchStart) {
                pendingName = classMatcher.group(1);
                nextClassMatchStart = classMatcher.find() ? classMatcher.start() : -1;
            }
            char c = masked.charAt(i);
            if (c == '{') {
                depth++;
                if (pendingName != null) {
                    events.add(new NestingEvent(i, true, pendingName));
                    depthStack.add(depth);
                    pendingName = null;
                }
            } else if (c == '}') {
                if (!depthStack.isEmpty() && depthStack.get(depthStack.size() - 1) == depth) {
                    depthStack.remove(depthStack.size() - 1);
                    events.add(new NestingEvent(i, false, null));
                }
                depth--;
            }
        }
        return events;
    }

    /** Returns the stack of simple class names (outermost first) enclosing {@code offset}, empty for top level. */
    private static List<String> enclosingClassAt(List<NestingEvent> events, int offset) {
        List<String> stack = new ArrayList<>();
        for (NestingEvent event : events) {
            if (event.offset >= offset) {
                break;
            }
            if (event.push) {
                stack.add(event.name);
            } else if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
        }
        return stack;
    }

    /**
     * Builds a JVM binary class name from a package, an enclosing class stack (outermost first,
     * possibly empty for top level), and an optional extra simple name to append (a class
     * currently being declared) - {@code pkg.Outer$Inner} form, matching japicmp/javap
     * convention exactly, with no stray separators for the top-level/no-extra-name cases.
     */
    private static String buildBinaryName(String packageName, List<String> classStack, String extraSimpleName) {
        List<String> parts = new ArrayList<>(classStack);
        if (extraSimpleName != null) {
            parts.add(extraSimpleName);
        }
        String chain = String.join("$", parts);
        if (packageName.isEmpty()) {
            return chain;
        }
        return chain.isEmpty() ? packageName : packageName + "." + chain;
    }

    private enum Kind {
        CLASS, METHOD, FIELD, CONSTRUCTOR
    }

    private static final class Declaration {
        boolean hasDeprecatedAnnotation;
        String since;
        boolean forRemoval;
        Kind kind;
        String name;
        String rawParams;
    }

    /**
     * Parses the text immediately following a javadoc comment: any leading annotations (looking
     * specifically for {@code @Deprecated} and its {@code since}/{@code forRemoval} arguments),
     * then the declaration itself, classifying it as a class, field, method, or constructor.
     */
    private static Declaration parseDeclarationAfter(String source, int fromOffset) {
        int i = fromOffset;
        int n = source.length();
        Declaration decl = new Declaration();
        while (true) {
            i = skipWhitespace(source, i);
            if (i >= n || source.charAt(i) != '@') {
                break;
            }
            int annotationStart = i;
            i++;
            int nameStart = i;
            while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                i++;
            }
            String annotationName = source.substring(nameStart, i);
            int afterName = skipWhitespace(source, i);
            String args = null;
            if (afterName < n && source.charAt(afterName) == '(') {
                int depth = 0;
                int j = afterName;
                do {
                    if (source.charAt(j) == '(') {
                        depth++;
                    } else if (source.charAt(j) == ')') {
                        depth--;
                    }
                    j++;
                } while (j < n && depth > 0);
                args = source.substring(afterName + 1, j - 1);
                i = j;
            }
            if ("Deprecated".equals(annotationName)) {
                decl.hasDeprecatedAnnotation = true;
                if (args != null) {
                    Matcher sinceMatcher = SINCE_ARG.matcher(args);
                    if (sinceMatcher.find()) {
                        decl.since = sinceMatcher.group(1);
                    }
                    Matcher forRemovalMatcher = FOR_REMOVAL_ARG.matcher(args);
                    if (forRemovalMatcher.find()) {
                        decl.forRemoval = Boolean.parseBoolean(forRemovalMatcher.group(1));
                    }
                }
            }
            if (annotationStart == i) {
                // Defensive: no progress made, avoid an infinite loop on malformed input.
                break;
            }
        }

        // From here to the first top-level '{' or ';' is the declaration signature.
        int declStart = skipWhitespace(source, i);
        int depth = 0;
        int j = declStart;
        while (j < n) {
            char c = source.charAt(j);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (c == '{' || c == ';')) {
                break;
            }
            j++;
        }
        if (j >= n) {
            return null;
        }
        String declText = source.substring(declStart, j);
        classifyDeclaration(declText, decl);
        return decl.kind == null ? null : decl;
    }

    private static int skipWhitespace(String source, int i) {
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void classifyDeclaration(String declText, Declaration decl) {
        Matcher classMatcher = CLASS_OPEN.matcher(declText);
        if (classMatcher.find()) {
            decl.kind = Kind.CLASS;
            decl.name = classMatcher.group(1);
            return;
        }
        int parenIndex = declText.indexOf('(');
        if (parenIndex >= 0) {
            // Method or constructor: the identifier immediately before the first '(' is the name.
            int nameEnd = parenIndex;
            int nameStart = nameEnd;
            while (nameStart > 0 && (Character.isLetterOrDigit(declText.charAt(nameStart - 1))
                    || declText.charAt(nameStart - 1) == '_')) {
                nameStart--;
            }
            String name = declText.substring(nameStart, nameEnd).trim();
            if (name.isEmpty()) {
                return;
            }
            int closeIndex = matchingClose(declText, parenIndex);
            if (closeIndex < 0) {
                return;
            }
            decl.name = name;
            decl.rawParams = declText.substring(parenIndex + 1, closeIndex);
            decl.kind = Kind.METHOD; // corrected to CONSTRUCTOR by the caller once the enclosing simple name is known
        } else {
            // Field: the last identifier token before the (already-stripped) trailing ';' or '='.
            String text = declText;
            int eq = text.indexOf('=');
            if (eq >= 0) {
                text = text.substring(0, eq);
            }
            text = text.trim();
            int lastSpace = text.lastIndexOf(' ');
            String name = lastSpace >= 0 ? text.substring(lastSpace + 1).trim() : text.trim();
            name = name.replaceAll("[^\\w]", "");
            if (name.isEmpty()) {
                return;
            }
            decl.kind = Kind.FIELD;
            decl.name = name;
        }
    }

    private static int matchingClose(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractDeprecatedBody(String comment) {
        Matcher matcher = DEPRECATED_TAG_BODY.matcher(comment);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        return raw.replaceAll("\\r?\\n[ \\t]*\\*[ \\t]?", " ").trim();
    }

    private static String extractRemoveInTag(String comment) {
        Matcher matcher = REMOVE_IN_TAG.matcher(comment);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ---- reflective FQN resolution ---------------------------------------------------------

    private DeprecationEntry buildEntry(String packageName, List<String> enclosingStack, Declaration decl, String since,
            boolean forRemoval, String removeIn, String replacement) throws ReflectiveOperationException {
        DeprecationEntry.Status status = forRemoval ? DeprecationEntry.Status.ANNOUNCED : DeprecationEntry.Status.DEPRECATED;

        if (decl.kind == Kind.CLASS) {
            String classBinaryName = buildBinaryName(packageName, enclosingStack, decl.name);
            Class<?> clazz = Class.forName(classBinaryName, false, classLoader);
            return DeprecationEntry.builder()
                    .key(RegistryKey.forClass(clazz.getName()))
                    .kind(DeprecationEntry.Kind.CLASS)
                    .since(since).forRemoval(forRemoval).removeIn(removeIn).replacement(replacement)
                    .status(status).build();
        }

        String enclosingBinaryName = buildBinaryName(packageName, enclosingStack, null);
        Class<?> owner = Class.forName(enclosingBinaryName, false, classLoader);
        String simpleEnclosingName = enclosingStack.isEmpty() ? "" : enclosingStack.get(enclosingStack.size() - 1);

        if (decl.kind == Kind.FIELD) {
            Field field = owner.getDeclaredField(decl.name);
            return DeprecationEntry.builder()
                    .key(RegistryKey.forField(owner.getName(), field.getName()))
                    .kind(DeprecationEntry.Kind.FIELD)
                    .since(since).forRemoval(forRemoval).removeIn(removeIn).replacement(replacement)
                    .status(status).build();
        }

        List<ParsedParam> parsedParams = parseParams(decl.rawParams);
        boolean isConstructor = decl.name.equals(simpleEnclosingName);

        if (isConstructor) {
            Constructor<?> ctor = findMatching(owner.getDeclaredConstructors(), parsedParams);
            List<String> fqParams = fullyQualifiedParamNames(ctor.getParameterTypes());
            return DeprecationEntry.builder()
                    .key(RegistryKey.forMember(owner.getName(), simpleEnclosingName, fqParams))
                    .kind(DeprecationEntry.Kind.CONSTRUCTOR)
                    .since(since).forRemoval(forRemoval).removeIn(removeIn).replacement(replacement)
                    .status(status).build();
        }

        List<Method> candidates = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(decl.name)) {
                candidates.add(m);
            }
        }
        Method method = findMatching(candidates.toArray(new Executable[0]), parsedParams);
        List<String> fqParams = fullyQualifiedParamNames(method.getParameterTypes());
        return DeprecationEntry.builder()
                .key(RegistryKey.forMember(owner.getName(), method.getName(), fqParams))
                .kind(DeprecationEntry.Kind.METHOD)
                .since(since).forRemoval(forRemoval).removeIn(removeIn).replacement(replacement)
                .status(status).build();
    }

    private static List<String> fullyQualifiedParamNames(Class<?>[] paramTypes) {
        List<String> names = new ArrayList<>();
        for (Class<?> type : paramTypes) {
            names.add(typeName(type));
        }
        return names;
    }

    private static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    private static final class ParsedParam {
        final String simpleType;
        final int arrayDepth;

        ParsedParam(String simpleType, int arrayDepth) {
            this.simpleType = simpleType;
            this.arrayDepth = arrayDepth;
        }
    }

    /**
     * Parses a raw, comma-separated (at depth 0) parameter list as written in source into simple
     * type names for reflective disambiguation - generics erased, array/varargs depth tracked
     * separately, parameter identifiers discarded.
     */
    private static List<ParsedParam> parseParams(String rawParams) {
        List<ParsedParam> result = new ArrayList<>();
        if (rawParams == null || rawParams.trim().isEmpty()) {
            return result;
        }
        for (String rawParam : splitTopLevel(rawParams)) {
            String param = rawParam.trim();
            if (param.isEmpty()) {
                continue;
            }
            param = param.replaceAll("^final\\s+", "");
            param = param.replaceAll("@\\w+(\\([^)]*\\))?\\s+", ""); // strip parameter annotations
            param = param.replace("...", "[]"); // varargs normalizes to an array type, same as japicmp/reflection see it
            int lastTopLevelSpace = lastTopLevelSpace(param);
            String typeText = lastTopLevelSpace >= 0 ? param.substring(0, lastTopLevelSpace).trim() : param.trim();
            int arrayDepth = 0;
            while (typeText.endsWith("[]")) {
                arrayDepth++;
                typeText = typeText.substring(0, typeText.length() - 2).trim();
            }
            int genericStart = typeText.indexOf('<');
            String simpleType = genericStart >= 0 ? typeText.substring(0, genericStart) : typeText;
            simpleType = simpleType.trim();
            int lastDot = simpleType.lastIndexOf('.');
            if (lastDot >= 0) {
                simpleType = simpleType.substring(lastDot + 1);
            }
            result.add(new ParsedParam(simpleType, arrayDepth));
        }
        return result;
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            } else if (c == '>' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static int lastTopLevelSpace(String text) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ' ' && depth == 0) {
                lastSpace = i;
            }
        }
        return lastSpace;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Executable> T findMatching(Executable[] candidates, List<ParsedParam> parsedParams) {
        List<Executable> matches = new ArrayList<>();
        for (Executable candidate : candidates) {
            Class<?>[] paramTypes = candidate.getParameterTypes();
            if (paramTypes.length != parsedParams.size()) {
                continue;
            }
            boolean allMatch = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!simpleTypeMatches(paramTypes[i], parsedParams.get(i))) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                matches.add(candidate);
            }
        }
        if (matches.size() == 1) {
            return (T) matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("No reflected member matched parsed parameters " + describe(parsedParams)
                    + " among " + candidates.length + " candidate(s) named the same");
        }
        throw new IllegalStateException("Ambiguous match (" + matches.size() + " candidates) for parsed parameters "
                + describe(parsedParams));
    }

    private static boolean simpleTypeMatches(Class<?> reflected, ParsedParam parsed) {
        Class<?> base = reflected;
        int depth = 0;
        while (base.isArray()) {
            base = base.getComponentType();
            depth++;
        }
        if (depth != parsed.arrayDepth) {
            return false;
        }
        return base.getSimpleName().equals(parsed.simpleType);
    }

    private static String describe(List<ParsedParam> params) {
        List<String> parts = new ArrayList<>();
        for (ParsedParam p : params) {
            StringBuilder sb = new StringBuilder(p.simpleType);
            for (int i = 0; i < p.arrayDepth; i++) {
                sb.append("[]");
            }
            parts.add(sb.toString());
        }
        return Arrays.toString(parts.toArray());
    }
}
