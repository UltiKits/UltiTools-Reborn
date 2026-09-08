package com.ultikits.ultitools.uat;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point carried by the framework jar (Phase 10, D-10-03): regenerates a module's
 * {@code uat/surface.json} from its own compiled classes.
 * <p>
 * Invoked from a module checkout over that module's own compile classpath, once the framework
 * jar is on {@code -cp}:
 * <pre>
 * mvn -q dependency:build-classpath -Dmdep.outputFile=target/uat-cp.txt
 * java -cp "$(cat target/uat-cp.txt):target/classes" com.ultikits.ultitools.uat.SurfaceExtractorMain \
 *     --module &lt;Name&gt; --classes target/classes --output uat/surface.json
 * </pre>
 * Copies {@code DeprecationRegistryGenerator.main}'s fail-closed shape: an {@link ExtractorException}
 * prints only its own message; anything else prints a prefixed message plus a stack trace. Both
 * cases call {@link System#exit(int)} with a non-zero status. Never exits {@code 0} on a
 * partially built document — the canonical write only happens after every class has resolved and
 * every row has been assembled without a collision.
 *
 * @since 6.3.0
 */
public final class SurfaceExtractorMain {

    private static final int SCHEMA_VERSION = 1;

    private SurfaceExtractorMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (ExtractorException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("SurfaceExtractorMain failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws ExtractorException, IOException {
        Map<String, String> options = parseArgs(args);
        String module = require(options, "--module");
        String classesArg = require(options, "--classes");
        String outputArg = options.get("--output");

        Path classesRoot = Paths.get(classesArg);

        ModuleClassIndex index = new ModuleClassIndex(Thread.currentThread().getContextClassLoader());
        List<Class<?>> classes = index.load(classesRoot);

        SurfaceAssembler assembler = new SurfaceAssembler();
        SurfaceAssembler.AssembledSurface surface = assembler.assemble(module, classes);

        if (outputArg != null) {
            CanonicalJsonWriter.write(Paths.get(outputArg), SCHEMA_VERSION, surface.getRows(), surface.getDocumentExtras());
        } else {
            System.out.print(CanonicalJsonWriter.toJsonString(SCHEMA_VERSION, surface.getRows(), surface.getDocumentExtras()));
        }
    }

    private static Map<String, String> parseArgs(String[] args) throws ExtractorException {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            if (i + 1 >= args.length) {
                throw new ExtractorException("Missing value for argument " + arg);
            }
            options.put(arg, args[++i]);
        }
        return options;
    }

    private static String require(Map<String, String> options, String key) throws ExtractorException {
        String value = options.get(key);
        if (value == null || value.isEmpty()) {
            throw new ExtractorException("Missing required argument " + key);
        }
        return value;
    }
}
