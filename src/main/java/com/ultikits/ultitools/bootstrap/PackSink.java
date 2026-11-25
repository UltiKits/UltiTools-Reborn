package com.ultikits.ultitools.bootstrap;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface PackSink {
    boolean discover(Path zip, String id) throws IOException;
}
