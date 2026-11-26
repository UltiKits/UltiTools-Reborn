package com.ultikits.ultitools.bootstrap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.ultikits.ultitools.utils.ModuleContentKind;

/**
 * Materializes framework-owned derived ZIPs from the declaring module JARs on each start. The
 * {@code bootstrap/datapacks} cache is not operator-editable and never targets the world's data
 * pack directory or any file supplied by the operator.
 */
final class EmbeddedDatapacks {
    private static final Pattern ZIP_NAME = Pattern.compile("[a-z0-9_.-]{1,128}\\.[a-z0-9_-]{1,32}\\.zip");

    private EmbeddedDatapacks() {
        // No per-server state in the cache builder.
    }

    static void prepare(Path dataFolder, ContributionRegistry registry, Logger logger) {
        Path directory = BootstrapFiles.datapacks(BootstrapFiles.root(dataFolder));
        List<PreparedPack> packs = new ArrayList<>();
        for (var module : registry.modules()) {
            if (!module.packNames().isEmpty() && !registry.failed(module.jar())) {
                packs.addAll(prepareModule(dataFolder, directory, registry, module));
            }
        }
        Set<Path> keep = packs.stream().map(PreparedPack::zip).collect(Collectors.toSet());
        prune(directory, keep, logger);
        registry.preparedPacks(packs);
    }

    private static List<PreparedPack> prepareModule(Path dataFolder, Path directory, ContributionRegistry registry,
                                                      ModuleContributions module) {
        List<PreparedPack> packs = new ArrayList<>();
        String id = module.packId(module.packNames().iterator().next());
        try (var archive = new ZipFile(module.jar().toFile())) {
            Files.createDirectories(dataFolder);
            BootstrapFiles.ensureDirectory(directory.getParent());
            BootstrapFiles.ensureDirectory(directory);
            for (String name : module.packNames()) {
                id = module.packId(name);
                // Recheck the actual archive about to be handed to Paper, even if it was replaced after declaration.
                String refused = ModuleContributions.worldgenDirectory(archive, name);
                if (refused != null) {
                    var rejection = new ModuleContributions.WorldgenRefusal(module.name(), name, refused);
                    registry.recordWorldgenRefusal(rejection);
                    throw rejection.failure();
                }
                Path zip = directory.resolve(id + ".zip");
                extract(archive, name, zip);
                packs.add(new PreparedPack(module, zip, id));
            }
            return packs;
        } catch (IOException | RuntimeException failure) {
            registry.failPreparation(module, ModuleContentKind.DATAPACK, id, failure);
            return List.of();
        }
    }

    private static void extract(ZipFile archive, String name, Path target) throws IOException {
        ModuleContributions.validatePack(archive, name);
        boolean exists = Files.exists(target, NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            throw new IOException("Not a derived data pack ZIP: " + target);
        }
        Path temporary = Files.createTempFile(target.getParent(), BootstrapFiles.PACK_TEMP_PREFIX, BootstrapFiles.TEMP_SUFFIX);
        try {
            writeZip(archive, name, temporary);
            if (!exists || !Arrays.equals(sha256(temporary), sha256(target))) {
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeZip(ZipFile archive, String name, Path temporary) throws IOException {
        String prefix = "datapacks/" + name + '/';
        var entries = archive.stream().filter(entry -> entry.getName().startsWith(prefix))
                .filter(entry -> !entry.getName().equals(prefix))
                .sorted(Comparator.comparing(ZipEntry::getName)).toList();
        try (var output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            for (ZipEntry entry : entries) {
                String relative = entry.getName().substring(prefix.length());
                validatePath(relative);
                ZipEntry copy = new ZipEntry(relative);
                copy.setTime(0L);
                output.putNextEntry(copy);
                try (var input = archive.getInputStream(entry)) {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
    }

    private static void validatePath(String path) throws IOException {
        if (path.startsWith("/") || path.contains("\\") || path.contains("//")) {
            throw new IOException("Invalid bundled data pack entry: " + path);
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IOException("Invalid bundled data pack entry: " + path);
            }
        }
    }

    private static byte[] sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime did not supply SHA-256", impossible);
        }
        try (var input = new DigestInputStream(Files.newInputStream(path, NOFOLLOW_LINKS), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static void prune(Path directory, Set<Path> keep, Logger logger) {
        try {
            if (Files.notExists(directory.getParent(), NOFOLLOW_LINKS)) {
                return;
            }
            BootstrapFiles.requireDirectory(directory.getParent());
            if (Files.notExists(directory, NOFOLLOW_LINKS)) {
                return;
            }
            BootstrapFiles.requireDirectory(directory);
            try (var files = Files.newDirectoryStream(directory, path -> ZIP_NAME.matcher(path.getFileName().toString()).matches())) {
                for (Path file : files) {
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS) && !keep.contains(file)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            ModuleBootstraps.log(logger, Level.WARNING, "[UltiTools] Could not prune the derived data pack cache ("
                    + directory + "): " + ModuleBootstraps.describe(failure), failure);
        }
    }

    record PreparedPack(ModuleContributions module, Path zip, String id) { }
}
