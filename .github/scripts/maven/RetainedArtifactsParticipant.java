package com.ultikits.release;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Assigns the retained, accepted release files to the one project Maven read, so the existing Central
 * publisher collects exactly those bytes instead of anything a build would produce.
 *
 * <p>Release publication runs no lifecycle phase. The publisher goal is invoked directly on a private copy of
 * the verified source {@code pom.xml}, the project model, and this participant, loaded as a core extension
 * through {@code -Dmaven.ext.class.path}, runs in {@code afterProjectsRead}. The publisher cleans its staging,
 * deferred and output directories only for the first project whose build declares the publisher plugin, so
 * the model is the source POM, whose build always does, rather than whatever the flattened POM keeps; this
 * participant refuses a model that does not declare it.
 * The participant then makes the retained flattened POM the project file with
 * {@link MavenProject#setPomFile(File)}, the way flatten-maven-plugin does, so the publisher stages exactly
 * that POM as the primary artifact's POM metadata; no POM is generated. Finally it sets the project's primary
 * artifact file and attaches the sources JAR, the javadoc JAR and the four detached signatures through public
 * {@link MavenProject} API.</p>
 *
 * <p>Every file comes from a properties file written by {@code release_artifacts.py central-inputs} after it
 * validated the release manifest and the detached signatures. This class re-checks every file's size and
 * SHA-256 digest immediately before assigning it and refuses the build on any difference, an unexpected
 * project, a project model other than the listed source POM, a model that does not declare the publisher, a
 * pre-assigned primary file, an existing attachment or a link.</p>
 */
public class RetainedArtifactsParticipant extends AbstractMavenLifecycleParticipant {

    /** User property naming the validated properties file. */
    public static final String PROPERTY = "retainedArtifacts.properties";

    static final String FORMAT = "ultitools-retained-artifacts/2";
    static final String GROUP_ID = "com.ultikits";
    static final String ARTIFACT_ID = "UltiTools-API";
    static final int ENTRY_COUNT = 8;

    /** Properties prefix of the project-model POM Maven reads; it is never uploaded. */
    static final String PROJECT_POM = "project_pom.";
    static final String PUBLISHER_GROUP_ID = "org.sonatype.central";
    static final String PUBLISHER_ARTIFACT_ID = "central-publishing-maven-plugin";

    private static final Pattern FORMAL_VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DIGITS = Pattern.compile("[1-9][0-9]{0,17}");

    /** The eight retained files: role, Maven type, classifier, file-name suffix after artifactId-version. */
    private static final String[][] LAYOUT = {
        {"pom", "pom", "", ".pom"},
        {"primary", "jar", "", ".jar"},
        {"sources", "jar", "sources", "-sources.jar"},
        {"javadoc", "jar", "javadoc", "-javadoc.jar"},
        {"pom-signature", "pom.asc", "", ".pom.asc"},
        {"primary-signature", "jar.asc", "", ".jar.asc"},
        {"sources-signature", "jar.asc", "sources", "-sources.jar.asc"},
        {"javadoc-signature", "jar.asc", "javadoc", "-javadoc.jar.asc"},
    };

    /**
     * {@inheritDoc}
     *
     * <p>{@link MavenProject#addAttachedArtifact} is deprecated in favour of the MavenProjectHelper component,
     * but it is public in Maven 3.8 and 3.9 and attaches the exact artifact object built here, with the
     * exact type, classifier and file, without a handler lookup that could change the extension.</p>
     */
    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String location = session.getUserProperties().getProperty(PROPERTY);
        if (location == null || location.isEmpty()) {
            throw refuse("properties_missing", "-D" + PROPERTY + " is not set; refusing to publish without the "
                    + "validated retained-file list", null);
        }
        Properties properties = load(new File(location));
        String version = checkProperties(properties);
        MavenProject project = checkedProject(session, version);
        File[] files = checkedFiles(properties, version);
        checkProjectModel(project, properties);
        bindPublicationPom(project, files[0]);
        assignArtifacts(project, files, version);
    }

    private static String checkProperties(Properties properties) throws MavenExecutionException {
        String version = properties.getProperty("version");
        checkKeys(properties);
        if (!FORMAT.equals(properties.getProperty("format"))
                || !GROUP_ID.equals(properties.getProperty("group_id"))
                || !ARTIFACT_ID.equals(properties.getProperty("artifact_id"))
                || version == null || !FORMAL_VERSION.matcher(version).matches()
                || !"jar".equals(properties.getProperty("packaging"))
                || !String.valueOf(ENTRY_COUNT).equals(properties.getProperty("count"))) {
            throw refuse("properties_identity", "the retained-file list does not name exactly "
                    + GROUP_ID + ":" + ARTIFACT_ID + " at a formal version with packaging jar and "
                    + ENTRY_COUNT + " files", null);
        }
        return version;
    }

    private static MavenProject checkedProject(MavenSession session, String version) throws MavenExecutionException {
        List<MavenProject> projects = session.getProjects();
        if (projects == null || projects.size() != 1) {
            throw refuse("project_count", "expected exactly one project read from the retained POM, got "
                    + (projects == null ? 0 : projects.size()), null);
        }
        MavenProject project = projects.get(0);
        Artifact primary = project.getArtifact();
        if (!GROUP_ID.equals(project.getGroupId()) || !ARTIFACT_ID.equals(project.getArtifactId())
                || !version.equals(project.getVersion()) || !"jar".equals(project.getPackaging())
                || primary == null || primary.isSnapshot()
                || !GROUP_ID.equals(primary.getGroupId()) || !ARTIFACT_ID.equals(primary.getArtifactId())
                || !version.equals(primary.getVersion()) || primary.getClassifier() != null) {
            throw refuse("project_identity", "the project read from the POM is not the non-SNAPSHOT jar "
                    + GROUP_ID + ":" + ARTIFACT_ID + ":" + version, null);
        }
        if (primary.getFile() != null) {
            throw refuse("primary_preassigned", "the project already carries a primary artifact file; only the "
                    + "retained file may be assigned", null);
        }
        if (!project.getAttachedArtifacts().isEmpty()) {
            throw refuse("attachments_preexisting", "the project already carries attached artifacts", null);
        }
        return project;
    }

    private static File[] checkedFiles(Properties properties, String version) throws MavenExecutionException {
        String base = ARTIFACT_ID + "-" + version;
        File[] files = new File[LAYOUT.length];
        File folder = null;
        for (int i = 0; i < LAYOUT.length; i++) {
            String[] row = LAYOUT[i];
            String prefix = "artifact." + (i + 1) + ".";
            if (!row[0].equals(properties.getProperty(prefix + "role"))
                    || !row[1].equals(properties.getProperty(prefix + "type"))
                    || !row[2].equals(properties.getProperty(prefix + "classifier"))) {
                throw refuse("entry_layout", "entry " + (i + 1) + " is not the expected " + row[0], null);
            }
            File file = new File(properties.getProperty(prefix + "file", ""));
            if (!file.isAbsolute() || !file.getName().equals(base + row[3])) {
                throw refuse("entry_name", "entry " + (i + 1) + " must be the absolute path of " + base + row[3], null);
            }
            File parent = file.getParentFile();
            if (folder == null) {
                folder = parent;
            } else if (!folder.equals(parent)) {
                throw refuse("entry_folder", "every retained file must sit in one inputs directory", null);
            }
            verify(file, properties.getProperty(prefix + "bytes"), properties.getProperty(prefix + "sha256"));
            files[i] = file;
        }
        return files;
    }

    /** The project model must be the listed, unchanged source POM, and it must declare the publisher. */
    private static void checkProjectModel(MavenProject project, Properties properties)
            throws MavenExecutionException {
        File model = new File(properties.getProperty(PROJECT_POM + "file", ""));
        if (!model.isAbsolute()) {
            throw refuse("project_pom_name", "the project-model POM must be named by an absolute path", null);
        }
        verify(model, properties.getProperty(PROJECT_POM + "bytes"), properties.getProperty(PROJECT_POM + "sha256"));
        File pom = project.getFile();
        if (pom == null || !canonical(pom).equals(canonical(model))) {
            throw refuse("pom_not_project_file", "Maven must read the project model from the listed source POM",
                    null);
        }
        if (!declaresPublisher(project)) {
            throw refuse("publisher_not_declared", "the project model does not declare " + PUBLISHER_GROUP_ID + ":"
                    + PUBLISHER_ARTIFACT_ID + " in its build, so the publisher would not clean its scratch "
                    + "directories", null);
        }
    }

    private static boolean declaresPublisher(MavenProject project) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (PUBLISHER_GROUP_ID.equals(plugin.getGroupId())
                    && PUBLISHER_ARTIFACT_ID.equals(plugin.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    /** The publisher stages the project file as the POM; make that the retained flattened POM. */
    private static void bindPublicationPom(MavenProject project, File retainedPom) throws MavenExecutionException {
        File basedir = project.getBasedir();
        project.setPomFile(retainedPom);
        if (!canonical(project.getFile()).equals(canonical(retainedPom)) || basedir == null
                || !basedir.equals(project.getBasedir())) {
            throw refuse("pom_binding", "the retained POM could not be bound as the uploaded project file", null);
        }
    }

    /** Uses the public Maven API to preserve exact artifact types without handler lookups. */
    @SuppressWarnings("deprecation")
    private static void assignArtifacts(MavenProject project, File[] files, String version)
            throws MavenExecutionException {
        String base = ARTIFACT_ID + "-" + version;
        project.getArtifact().setFile(files[1]);
        try {
            for (int i = 2; i < LAYOUT.length; i++) {
                String[] row = LAYOUT[i];
                DefaultArtifactHandler handler = new DefaultArtifactHandler(row[1]);
                handler.setExtension(row[1]);
                handler.setLanguage("none");
                handler.setAddedToClasspath(false);
                DefaultArtifact attached = new DefaultArtifact(GROUP_ID, ARTIFACT_ID, version, null, row[1],
                        row[2].isEmpty() ? null : row[2], handler);
                attached.setFile(files[i]);
                project.addAttachedArtifact(attached);
            }
        } catch (RuntimeException e) {
            throw refuse("attach_failed", "attaching the retained files failed: " + e.getMessage(), e);
        }
        if (project.getAttachedArtifacts().size() != LAYOUT.length - 2) {
            throw refuse("attachment_count", "expected " + (LAYOUT.length - 2) + " attachments, got "
                    + project.getAttachedArtifacts().size(), null);
        }
        System.out.println("[retained-artifacts] assigned " + base + ".jar and " + (LAYOUT.length - 2)
                + " attachments from the validated retained-file list");
    }

    private static void checkKeys(Properties properties) throws MavenExecutionException {
        Set<String> expected = new HashSet<String>(Arrays.asList("format", "group_id", "artifact_id", "version",
                "packaging", "count", PROJECT_POM + "file", PROJECT_POM + "bytes", PROJECT_POM + "sha256"));
        for (int i = 1; i <= ENTRY_COUNT; i++) {
            for (String field : new String[] {"role", "type", "classifier", "file", "bytes", "sha256"}) {
                expected.add("artifact." + i + "." + field);
            }
        }
        Set<String> actual = new TreeSet<String>(properties.stringPropertyNames());
        if (!actual.equals(expected)) {
            Set<String> unknown = new TreeSet<String>(actual);
            unknown.removeAll(expected);
            Set<String> missing = new TreeSet<String>(expected);
            missing.removeAll(actual);
            throw refuse("properties_keys", "unexpected keys " + unknown + ", missing keys " + missing, null);
        }
    }

    private static Properties load(File file) throws MavenExecutionException {
        regular(file, "properties_file");
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS);
            properties.load(in);
        } catch (IOException e) {
            throw refuse("properties_unreadable", "cannot read the retained-file list", e);
        } finally {
            close(in);
        }
        return properties;
    }

    private static void verify(File file, String bytes, String sha256) throws MavenExecutionException {
        if (bytes == null || !DIGITS.matcher(bytes).matches() || sha256 == null || !HEX64.matcher(sha256).matches()) {
            throw refuse("entry_format", file.getName() + ": size or digest is malformed", null);
        }
        long size = regular(file, "entry_file");
        if (size != Long.parseLong(bytes)) {
            throw refuse("bytes_mismatch", file.getName() + ": listed " + bytes + " bytes, found " + size, null);
        }
        if (!sha256.equals(digest(file))) {
            throw refuse("sha256_mismatch", file.getName() + ": bytes differ from the validated digest", null);
        }
    }

    private static long regular(File file, String code) throws MavenExecutionException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw refuse(code, file.getName() + " must be a regular file, not a link", null);
            }
            return attributes.size();
        } catch (IOException e) {
            throw refuse(code, file.getName() + " is missing or unreadable", e);
        }
    }

    private static String digest(File file) throws MavenExecutionException {
        InputStream in = null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            in = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (IOException e) {
            throw refuse("entry_unreadable", file.getName() + " cannot be read", e);
        } catch (NoSuchAlgorithmException e) {
            throw refuse("sha256_unavailable", "SHA-256 is not available", e);
        } finally {
            close(in);
        }
    }

    private static String canonical(File file) throws MavenExecutionException {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            throw refuse("path_unresolvable", file.getName() + " cannot be resolved", e);
        }
    }

    private static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a read-only stream cannot lose data.
            }
        }
    }

    private static MavenExecutionException refuse(String code, String detail, Throwable cause) {
        return new MavenExecutionException("retained-artifacts refused: " + code + ": " + detail, cause);
    }
}
