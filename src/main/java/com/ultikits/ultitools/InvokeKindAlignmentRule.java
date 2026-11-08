package com.ultikits.ultitools;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.ConstantDynamic;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Aligns server-owned method references with the real owner's class/interface kind. */
final class InvokeKindAlignmentRule implements ClassRewriteRule {
    private static final String[] SERVER_NAMESPACES = {
        "org/bukkit/", "io/papermc/", "com/destroystokyo/", "net/kyori/", "co/aikar/"
    };
    private final ServerApiProbe probe;
    private final ConcurrentMap<String, Integer> rewrittenSites = new ConcurrentHashMap<>();

    InvokeKindAlignmentRule(ServerApiProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public boolean wouldChange(String binaryName, byte[] bytes) {
        AlignmentVisitor visitor = new AlignmentVisitor(binaryName, null);
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.changes != 0;
    }

    @Override
    public byte[] rewrite(String binaryName, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        AlignmentVisitor visitor = new AlignmentVisitor(binaryName, writer);
        reader.accept(visitor, 0);
        if (visitor.changes == 0) {
            return bytes;
        }
        byte[] result = writer.toByteArray();
        rewrittenSites.put(binaryName, visitor.changes);
        return result;
    }

    @Override
    public boolean covers(ServerApiReference reference) {
        boolean method = reference.getKind() == ServerApiReference.Kind.METHOD;
        boolean handle = reference.getKind() == ServerApiReference.Kind.METHOD_HANDLE;
        if ((!method && !handle) || reference.getName() == null || reference.getDescriptor() == null) {
            return false;
        }
        int opcode = reference.getOpcode();
        boolean supported = method
                ? opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESTATIC
                : opcode == Opcodes.H_INVOKEINTERFACE || opcode == Opcodes.H_INVOKEVIRTUAL || opcode == Opcodes.H_INVOKESTATIC;
        return supported && mismatch(reference.getOwner(), reference.isInterfaceOwner()) != null
                && probe.hasMethod(reference.getOwner(), reference.getName(), reference.getDescriptor());
    }

    /** Read only after defineClass succeeds; a failed definition contributes no startup counts. */
    int rewrittenSites(String binaryName) {
        return rewrittenSites.getOrDefault(binaryName, 0);
    }

    private static boolean serverOwned(String owner) {
        if (owner != null) {
            for (String namespace : SERVER_NAMESPACES) {
                if (owner.startsWith(namespace)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Null means either no known owner or no mismatch, never a guessed replacement. */
    private Boolean mismatch(String owner, boolean interfaceOwner) {
        if (!serverOwned(owner)) {
            return null;
        }
        ServerApiTypeKind kind = probe.typeKind(owner);
        if (kind == ServerApiTypeKind.ABSENT) {
            return null;
        }
        boolean actualInterface = kind == ServerApiTypeKind.INTERFACE;
        return actualInterface == interfaceOwner ? null : actualInterface;
    }

    private final class AlignmentVisitor extends ClassVisitor {
        private final String binaryName;
        private int changes;

        AlignmentVisitor(String binaryName, ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
            this.binaryName = binaryName;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superclass, String[] interfaces) {
            checkSupertype(superclass, false, "extends");
            if (interfaces != null) {
                for (String superinterface : interfaces) {
                    checkSupertype(superinterface, true, "implements");
                }
            }
            super.visit(version, access, name, signature, superclass, interfaces);
        }

        private void checkSupertype(String owner, boolean expectedInterface, String relationship) {
            Boolean actual = mismatch(owner, expectedInterface);
            if (actual != null) {
                throw new IllegalStateException("class " + binaryName + " " + relationship + " " + owner
                        + ", which is " + (actual ? "an interface" : "a class") + " on this server");
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                    int alignedOpcode = opcode;
                    boolean alignedInterface = itf;
                    Boolean actual = mismatch(owner, itf);
                    if (actual != null) {
                        if (opcode == Opcodes.INVOKESPECIAL) {
                            throw special(owner, member);
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESTATIC) {
                            alignedOpcode = opcode == Opcodes.INVOKESTATIC ? opcode
                                    : actual ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                            alignedInterface = actual;
                            changes++;
                        }
                    }
                    super.visitMethodInsn(alignedOpcode, owner, member, desc, alignedInterface);
                }

                @Override
                public void visitInvokeDynamicInsn(String member, String desc, Handle bootstrap, Object... arguments) {
                    Handle alignedBootstrap = alignHandle(bootstrap);
                    Object[] alignedArguments = arguments.clone();
                    for (int index = 0; index < alignedArguments.length; index++) {
                        alignedArguments[index] = alignConstant(alignedArguments[index]);
                    }
                    super.visitInvokeDynamicInsn(member, desc, alignedBootstrap, alignedArguments);
                }

                @Override
                public void visitLdcInsn(Object constant) {
                    super.visitLdcInsn(alignConstant(constant));
                }
            };
        }

        private Object alignConstant(Object constant) {
            if (constant instanceof Handle) {
                return alignHandle((Handle) constant);
            }
            if (constant instanceof ConstantDynamic) {
                ConstantDynamic dynamic = (ConstantDynamic) constant;
                int before = changes;
                Handle bootstrap = alignHandle(dynamic.getBootstrapMethod());
                Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
                for (int index = 0; index < arguments.length; index++) {
                    arguments[index] = alignConstant(dynamic.getBootstrapMethodArgument(index));
                }
                if (changes != before) {
                    return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(), bootstrap, arguments);
                }
            }
            return constant;
        }

        private Handle alignHandle(Handle handle) {
            int tag = handle.getTag();
            // Field handles have different descriptors and are not invoke-kind rewrites.
            if (tag < Opcodes.H_INVOKEVIRTUAL) {
                return handle;
            }
            Boolean actual = mismatch(handle.getOwner(), handle.isInterface());
            if (actual == null) {
                return handle;
            }
            if (tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_NEWINVOKESPECIAL) {
                throw special(handle.getOwner(), handle.getName());
            }
            if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_INVOKESTATIC) {
                int alignedTag = tag == Opcodes.H_INVOKESTATIC ? tag
                        : actual ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
                changes++;
                return new Handle(alignedTag, handle.getOwner(), handle.getName(), handle.getDesc(), actual);
            }
            return handle;
        }

        private IllegalStateException special(String owner, String member) {
            return new IllegalStateException("class " + binaryName + " has an unsupported invokespecial reference to "
                    + owner + "." + member + " whose class/interface kind changed on this server");
        }
    }
}
