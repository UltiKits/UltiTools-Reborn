package com.ultikits.ultitools.abstracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.ultikits.ultitools.annotations.ConfigEntry;

/**
 * Real save-failure protection (#574), retrospectively proven against the integration parent.
 * Requires Linux and Python 3 with resource.prlimit; unsupported setup fails rather than skips.
 * Only an owned child JVM receives the kernel file-size limit, after its complete initialization.
 */
class ConfigSaveTruncationTest {
    @TempDir Path tempDir;

    public static class Values extends AbstractConfigEntity {
        @ConfigEntry(path = "value") String value = "default";
        public Values(String path) { super(path); }
    }

    /** A separate JVM lets the same kernel refusal exercise both old and new real I/O paths. */
    public static final class SaveChild {
        private SaveChild() { }
        public static void main(String[] args) throws Exception {
            Path file = java.nio.file.Paths.get(args[0]);
            UltiToolsPlugin plugin = Mockito.mock(UltiToolsPlugin.class);
            when(plugin.getConfigFolder()).thenReturn(file.getParent().toString());
            when(plugin.getConfigFile(anyString())).thenReturn(file.toFile());
            Values config = new Values(file.getFileName().toString());
            config.init(plugin);
            config.value = String.join("", Collections.nCopies(1024, "x"));
            System.out.println("READY");
            System.out.flush();
            if (!"GO".equals(new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                    StandardCharsets.UTF_8)).readLine())) {
                throw new IllegalStateException("Missing owned-child start signal");
            }
            try {
                config.save();
                throw new IllegalStateException("Kernel write refusal did not occur");
            } catch (IOException failure) {
                System.out.println("SAVE_FAILED file=" + file.getFileName() + " cause="
                        + failure.getClass().getSimpleName() + " dirty=" + config.isModifiedSinceSnapshot());
            }
        }
    }

    @Test
    void realMidWriteFailureNeverTruncatesExistingFile() throws Exception {
        Path file = tempDir.resolve("truncation.yml");
        byte[] original = "# operator content\nvalue: original\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        Path output = tempDir.resolve("owned-child-output.txt");
        String supervisor = "import os,resource,selectors,signal,subprocess,sys,time\n"
                + "signal.signal(signal.SIGXFSZ, signal.SIG_IGN)\n"
                + "def terminate(signum, frame): raise RuntimeError('Owned supervisor terminated')\n"
                + "signal.signal(signal.SIGTERM, terminate)\n"
                + "child = subprocess.Popen(sys.argv[1:], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)\n"
                + "try:\n"
                + " selector=selectors.DefaultSelector(); selector.register(child.stdout, selectors.EVENT_READ)\n"
                + " deadline=time.monotonic()+45; ready=False\n"
                + " while time.monotonic()<deadline:\n"
                + "  if not selector.select(max(0, deadline-time.monotonic())): break\n"
                + "  line=child.stdout.readline()\n"
                + "  if not line: break\n"
                + "  sys.stdout.buffer.write(line); sys.stdout.flush()\n"
                + "  if line.strip()==b'READY': ready=True; break\n"
                + " if not ready: raise RuntimeError('Owned child readiness failed; not defect evidence')\n"
                + " resource.prlimit(child.pid, resource.RLIMIT_FSIZE, (64,64))\n"
                + " if resource.prlimit(child.pid, resource.RLIMIT_FSIZE)!=(64,64): raise RuntimeError('Limit not effective')\n"
                + " print('LIMIT_EFFECTIVE=64', flush=True)\n"
                + " child.stdin.write(b'GO\\n'); child.stdin.flush()\n"
                + " result,_=child.communicate(timeout=30); sys.stdout.buffer.write(result); sys.stdout.flush()\n"
                + " if child.returncode!=0: raise RuntimeError('Owned child exit '+str(child.returncode))\n"
                + "finally:\n"
                + " if child.poll() is None: child.kill(); child.wait(timeout=5)\n";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = new File(System.getProperty("java.home"), "bin/java").toString();
        ProcessBuilder builder = new ProcessBuilder("python3", "-u", "-c", supervisor,
                java, "-cp", classpath, SaveChild.class.getName(), file.toString());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            boolean completed = process.waitFor(85, TimeUnit.SECONDS);
            String transcript = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            assertThat(completed).as("bounded owned-child completion: %s", transcript).isTrue();
            assertThat(process.exitValue()).as("setup and child exit, not a defect RED: %s", transcript).isZero();
            assertThat(transcript).contains("LIMIT_EFFECTIVE=64", "SAVE_FAILED file=truncation.yml", "dirty=true");
            assertThat(Files.readAllBytes(file)).as("an actual mid-write IOException must preserve operator bytes").isEqualTo(original);
        } finally {
            // Terminate only processes this fixture owns, even if the Python supervisor stalls.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) { process.destroyForcibly(); }
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
