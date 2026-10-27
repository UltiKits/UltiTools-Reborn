package com.ultikits.ultitools.manager;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.ApiStatus;

import com.ultikits.ultitools.websocket.CloudRequestHandler;

/**
 * Builds the protocol v2 file and {@code server.properties} request handlers of this package for the connection wiring
 * in {@code com.ultikits.ultitools.utils} (plan 22-21); internal, not for module use. The handlers themselves stay
 * package-private: this class exposes only the {@link CloudRequestHandler} they implement.
 * <p>
 * Both handlers do blocking file I/O. The router runs them on the agent connection's thread, never on the server
 * thread.
 *
 * @since 6.3.1
 */
@ApiStatus.Internal
public final class RemoteRequestHandlers {

    /** The request types the file handler answers. */
    public static final List<String> FILE_TYPES = Collections.unmodifiableList(
            Arrays.asList("files.list", "files.read", "files.write", "files.delete"));

    /** The request types the {@code server.properties} handler answers. */
    public static final List<String> PROPERTY_TYPES = Collections.unmodifiableList(
            Arrays.asList("properties.get", "properties.update"));

    private RemoteRequestHandlers() {
    }

    /**
     * @param files      the file manager whose guard resolves every remote path
     * @param serverRoot the server root the editable roots are listed relative to
     * @param actionLog  receives one entry per decided remote file request
     * @return the handler of {@link #FILE_TYPES}
     */
    public static CloudRequestHandler files(FileOperationManager files, File serverRoot,
                                            Consumer<RemoteActionLog.Entry> actionLog) {
        return new RemoteFileOperations(files, serverRoot, actionLog);
    }

    /**
     * @param properties the {@code server.properties} manager
     * @param actionLog  receives one entry per remote property update
     * @return the handler of {@link #PROPERTY_TYPES}
     */
    public static CloudRequestHandler properties(ServerPropertiesManager properties,
                                                 Consumer<RemoteActionLog.Entry> actionLog) {
        return new RemotePropertiesHandler(properties, actionLog);
    }
}
