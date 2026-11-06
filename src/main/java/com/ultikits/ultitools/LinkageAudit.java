package com.ultikits.ultitools;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ConstantDynamic;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Read-only startup and offline audit of symbolic server/framework references, not a verifier.
 * Reflection, string lookups, NMS and behaviour behind unchanged signatures are out of scope.
 */
final class LinkageAudit {
    private LinkageAudit() {
        // Utility class; use the static audit entry points.
    }

    static List<Finding> scan(File module, ClassLoader api, List<ClassRewriteRule> rules) throws IOException {
        LinkageApiIndex index = new LinkageApiIndex(api);
        List<Finding> findings = new ArrayList<>();
        try (JarFile jar = new JarFile(module)) {
            for (JarEntry entry : effectiveClasses(jar)) {
                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    reader.accept(new Scanner(reader.getClassName(), index, rules, findings), ClassReader.SKIP_FRAMES);
                }
            }
        }
        return Collections.unmodifiableList(findings);
    }

    private static Iterable<JarEntry> effectiveClasses(JarFile jar) throws IOException {
        boolean multiRelease = jar.getManifest() != null
                && "true".equalsIgnoreCase(jar.getManifest().getMainAttributes().getValue("Multi-Release"));
        String javaVersion = System.getProperty("java.specification.version");
        int runtime = Integer.parseInt(javaVersion.startsWith("1.") ? javaVersion.substring(2) : javaVersion);
        Map<String, JarEntry> selected = new LinkedHashMap<>();
        Map<String, Integer> versions = new HashMap<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            selectClassEntry(entries.nextElement(), multiRelease, runtime, selected, versions);
        }
        return selected.values();
    }

    private static void selectClassEntry(JarEntry entry, boolean multiRelease, int runtime,
            Map<String, JarEntry> selected, Map<String, Integer> versions) {
        String name = entry.getName();
        int version = 0;
        if (name.startsWith("META-INF/versions/") && multiRelease) {
            String versioned = name.substring("META-INF/versions/".length());
            int slash = versioned.indexOf('/');
            if (slash < 0) {
                return;
            }
            try {
                version = Integer.parseInt(versioned.substring(0, slash));
            } catch (NumberFormatException invalidEntry) {
                return;
            }
            if (version < 9 || version > runtime) {
                return;
            }
            name = versioned.substring(slash + 1);
        }
        if (name.startsWith("META-INF/") || !name.endsWith(".class")) {
            return;
        }
        if (!versions.containsKey(name) || version > versions.get(name)) {
            selected.put(name, entry);
            versions.put(name, version);
        }
    }

    static void warn(File module, ClassLoader api, List<ClassRewriteRule> rules, Logger logger) throws IOException {
        for (Finding finding : scan(module, api, rules)) {
            logger.warning(finding.message(module.getName()));
        }
    }

    /** Explicit API paths never inherit the host process's Paper API. Additional API jars are repeatable. */
    static int run(String[] arguments, PrintStream output) throws IOException {
        List<URL> apis = new ArrayList<>();
        List<File> modules = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            if ("--api".equals(arguments[i]) && i + 1 < arguments.length) {
                for (String path : arguments[++i].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    apis.add(new File(path).toURI().toURL());
                }
            } else if (arguments[i].startsWith("--")) {
                output.println("Usage: LinkageAudit --api <api.jar[:dependency.jar...]> <module.jar>...");
                return 2;
            } else {
                modules.add(new File(arguments[i]));
            }
        }
        if (apis.isEmpty() || modules.isEmpty()) {
            output.println("Usage: LinkageAudit --api <api.jar[:dependency.jar...]> <module.jar>...");
            return 2;
        }
        // The executing framework supplies its own API, but never supplies a fallback server API.
        apis.add(LinkageAudit.class.getProtectionDomain().getCodeSource().getLocation());
        int status = 0;
        try (URLClassLoader loader = new URLClassLoader(apis.toArray(new URL[0]), null);
                ModuleClassLoader moduleLoader = new ModuleClassLoader(new URL[0], loader, null)) {
            for (File module : modules) {
                List<Finding> findings = moduleLoader.audit(module);
                for (Finding finding : findings) {
                    output.println(finding.message(module.getName()));
                }
                if (!findings.isEmpty()) {
                    status = 1;
                }
            }
        }
        return status;
    }

    /** The enclosing type is package-private; this launcher is not a published Java API. */
    public static void main(String[] arguments) throws IOException {
        System.exit(run(arguments, System.out));
    }

    private static boolean apiOwner(String owner) {
        return owner.startsWith("org/bukkit/") || owner.startsWith("io/papermc/")
                || owner.startsWith("com/destroystokyo/") || owner.startsWith("net/kyori/")
                || owner.startsWith("co/aikar/") || owner.startsWith("org/spigotmc/")
                || owner.startsWith("com/ultikits/ultitools/");
    }

    static final class Finding {
        final ServerApiReference reference;
        final String referringClass;
        final String referringMember;
        final int line;
        final List<String> problems;

        Finding(ServerApiReference reference, String referringClass, String referringMember, int line,
                List<String> problems) {
            this.reference = reference;
            this.referringClass = referringClass;
            this.referringMember = referringMember;
            this.line = line;
            this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
        }

        String message(String module) {
            String member = reference.getName() == null ? "" : "." + reference.getName() + reference.getDescriptor();
            return "Module '" + module + "': " + referringClass.replace('/', '.') + "." + referringMember
                    + (line > 0 ? " (line " + line + ")" : "") + " references "
                    + reference.getOwner().replace('/', '.') + member + ": " + String.join(", ", problems)
                    + " on this server; this code path may throw a LinkageError when reached. Continuing module loading.";
        }
    }

    private static final class Scanner extends ClassVisitor {
        private final String caller;
        private final LinkageApiIndex index;
        private final List<ClassRewriteRule> rules;
        private final List<Finding> findings;

        Scanner(String caller, LinkageApiIndex index, List<ClassRewriteRule> rules, List<Finding> findings) {
            super(Opcodes.ASM9);
            this.caller = caller;
            this.index = index;
            this.rules = rules;
            this.findings = findings;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
            type(parent, ServerApiReference.Kind.SUPERCLASS, "<class>", 0);
            for (String iface : interfaces) {
                type(iface, ServerApiReference.Kind.INTERFACE, "<class>", 0);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            descriptor(descriptor, "<field> " + name, 0);
            constant(value, "<field> " + name, 0);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String member = name + descriptor;
            descriptor(descriptor, member, 0);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    type(exception, ServerApiReference.Kind.TYPE, member, 0);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                private int line;

                @Override
                public void visitLineNumber(int number, Label start) {
                    line = number;
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                    check(new ServerApiReference(ServerApiReference.Kind.METHOD, owner, method, desc, opcode, isInterface),
                            member, line);
                    descriptor(desc, member, line);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    check(new ServerApiReference(ServerApiReference.Kind.FIELD, owner, field, desc, opcode, false), member, line);
                    descriptor(desc, member, line);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    type(type, ServerApiReference.Kind.TYPE, member, line);
                }

                @Override
                public void visitMultiANewArrayInsn(String desc, int dimensions) {
                    descriptor(desc, member, line);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String exception) {
                    type(exception, ServerApiReference.Kind.TYPE, member, line);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value, member, line);
                }

                @Override
                public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap, Object... arguments) {
                    descriptor(desc, member, line);
                    constant(bootstrap, member, line);
                    for (Object argument : arguments) {
                        constant(argument, member, line);
                    }
                }
            };
        }

        private void constant(Object value, String member, int line) {
            if (value instanceof Type) {
                descriptor(((Type) value).getDescriptor(), member, line);
            } else if (value instanceof Handle) {
                Handle handle = (Handle) value;
                ServerApiReference.Kind kind = handle.getTag() <= Opcodes.H_PUTSTATIC
                        ? ServerApiReference.Kind.FIELD_HANDLE : ServerApiReference.Kind.METHOD_HANDLE;
                check(new ServerApiReference(kind, handle.getOwner(), handle.getName(), handle.getDesc(),
                        handle.getTag(), handle.isInterface()), member, line);
                descriptor(handle.getDesc(), member, line);
            } else if (value instanceof ConstantDynamic) {
                ConstantDynamic dynamic = (ConstantDynamic) value;
                descriptor(dynamic.getDescriptor(), member, line);
                constant(dynamic.getBootstrapMethod(), member, line);
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    constant(dynamic.getBootstrapMethodArgument(i), member, line);
                }
            }
        }

        private void descriptor(String descriptor, String member, int line) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) {
                for (Type argument : type.getArgumentTypes()) {
                    descriptor(argument.getDescriptor(), member, line);
                }
                descriptor(type.getReturnType().getDescriptor(), member, line);
            } else if (type.getSort() == Type.ARRAY) {
                descriptor(type.getElementType().getDescriptor(), member, line);
            } else if (type.getSort() == Type.OBJECT) {
                type(type.getInternalName(), ServerApiReference.Kind.TYPE, member, line);
            }
        }

        private void type(String owner, ServerApiReference.Kind kind, String member, int line) {
            if (owner == null) {
                return;
            }
            if (owner.startsWith("[")) {
                descriptor(owner, member, line);
                return;
            }
            check(new ServerApiReference(kind, owner, null, null, -1, kind == ServerApiReference.Kind.INTERFACE), member, line);
        }

        private void check(ServerApiReference reference, String member, int line) {
            String owner = reference.getOwner();
            if (!apiOwner(owner)) {
                return;
            }
            ServerApiTypeKind kind = index.typeKind(owner);
            List<String> problems = new ArrayList<>();
            if (kind == ServerApiTypeKind.ABSENT) {
                problems.add("MISSING-CLASS");
            } else {
                boolean isInterface = kind == ServerApiTypeKind.INTERFACE;
                switch (reference.getKind()) {
                    case SUPERCLASS:
                    case INTERFACE:
                        if (reference.isInterfaceOwner() != isInterface) {
                            problems.add("KIND");
                        }
                        break;
                    case METHOD:
                    case METHOD_HANDLE:
                        if (reference.isInterfaceOwner() != isInterface) {
                            problems.add("KIND");
                        }
                        if (!index.hasMethod(owner, reference.getName(), reference.getDescriptor())) {
                            problems.add("MISSING-METHOD");
                        }
                        break;
                    case FIELD:
                    case FIELD_HANDLE:
                        if (!index.hasField(owner, reference.getName(), reference.getDescriptor())) {
                            problems.add("MISSING-FIELD");
                        }
                        break;
                    default:
                        break;
                }
            }
            if (problems.isEmpty()) {
                return;
            }
            for (ClassRewriteRule rule : rules) {
                if (rule.covers(reference)) {
                    return;
                }
            }
            findings.add(new Finding(reference, caller, member, line, problems));
        }
    }
}
