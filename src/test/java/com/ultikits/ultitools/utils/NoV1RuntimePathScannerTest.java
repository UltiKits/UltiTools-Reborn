package com.ultikits.ultitools.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

class NoV1RuntimePathScannerTest {

    private static final String CLIENT = "com/ultikits/ultitools/websocket/UltiPanelWebSocketClient";
    private static final String CLIENT_DESC = "L" + CLIENT + ";";
    private static final String RECEIVER = "probe/Receiver";

    @Test
    void scanAcceptsACompiledRecordWithFieldHandles() throws IOException {
        NoV1RuntimePathTest.Scan scan = NoV1RuntimePathTest.Scan.of(
                NoV1RuntimePathTest.classBytes(RecordProbe.class));

        assertThat(scan.classCount).isEqualTo(1);
        assertThat(scan.findings).isEmpty();
        assertThat(scan.entryPoints).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC})
    void scanDetectsForbiddenTypesInFieldHandles(int tag) throws IOException {
        NoV1RuntimePathTest.Scan scan = scanWithBootstrapArgument(
                new Handle(tag, RECEIVER, "client", CLIENT_DESC, false));

        assertThat(scan.findings).isNotEmpty().allSatisfy(finding ->
                assertThat(finding).endsWith(" references a v1 client field Receiver.client"));
    }

    @ParameterizedTest
    @ValueSource(ints = {Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC})
    void scanAcceptsOrdinaryTypesInFieldHandles(int tag) throws IOException {
        NoV1RuntimePathTest.Scan scan = scanWithBootstrapArgument(
                new Handle(tag, RECEIVER, "value", "Ljava/lang/String;", false));

        assertThat(scan.findings).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC,
        Opcodes.H_INVOKESPECIAL, Opcodes.H_INVOKEINTERFACE})
    void scanStillDetectsClientHandoffsInMethodHandles(int tag) throws IOException {
        NoV1RuntimePathTest.Scan scan = scanWithBootstrapArgument(
                new Handle(tag, RECEIVER, "accept", "(" + CLIENT_DESC + ")V",
                        tag == Opcodes.H_INVOKEINTERFACE));

        assertThat(scan.findings).isNotEmpty().allSatisfy(finding ->
                assertThat(finding).endsWith(" hands a v1 client to Receiver.accept"));
    }

    @Test
    void scanStillDetectsClientConstructorHandles() throws IOException {
        NoV1RuntimePathTest.Scan scan = scanWithBootstrapArgument(
                new Handle(Opcodes.H_NEWINVOKESPECIAL, CLIENT, "<init>", "()V", false));

        assertThat(scan.findings).isNotEmpty().allSatisfy(finding ->
                assertThat(finding).endsWith(" constructs " + CLIENT));
    }

    private static NoV1RuntimePathTest.Scan scanWithBootstrapArgument(Handle replacement) throws IOException {
        byte[] record = NoV1RuntimePathTest.classBytes(RecordProbe.class).get(0);
        ClassReader reader = new ClassReader(record);
        ClassWriter writer = new ClassWriter(0);
        int[] replacements = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                            Handle bootstrap, Object... arguments) {
                        Object[] rewritten = arguments.clone();
                        for (int i = 0; i < rewritten.length; i++) {
                            if (rewritten[i] instanceof Handle
                                    && ((Handle) rewritten[i]).getTag() == Opcodes.H_GETFIELD) {
                                rewritten[i] = replacement;
                                replacements[0]++;
                            }
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrap, rewritten);
                    }
                };
            }
        }, 0);
        assertThat(replacements[0]).as("compiled record bootstrap field handles rewritten").isPositive();
        return NoV1RuntimePathTest.Scan.of(Collections.singletonList(writer.toByteArray()));
    }

    record RecordProbe(String value) {
    }
}
