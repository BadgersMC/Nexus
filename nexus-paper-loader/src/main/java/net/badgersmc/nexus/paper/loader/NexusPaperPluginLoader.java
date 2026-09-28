package net.badgersmc.nexus.paper.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared Paper {@link PluginLoader} declaring the standard Nexus runtime
 * library set. Consumer plugins extend this class and optionally override
 * {@link #additionalLibraries()} to add their own coordinates.
 * <p>
 * <strong>This class must remain Java.</strong> {@code PluginLoader.classloader()}
 * runs before any runtime libraries are resolved, so the loader's bytecode
 * cannot reference kotlin-stdlib classes (Intrinsics, etc.). Paper loads this
 * in an isolated early classloader that only sees paper-api plus the plugin
 * jar's own classes.
 * <p>
 * The default coordinates use {@code repo1.maven.org} directly rather than the
 * server's configured Maven Central mirror — some Leaf forks hardcode
 * {@code maven.aliyun.com}, which has had outages.
 * <p>
 * Keep your shadowJar excludes in sync with the libraries declared here.
 * Otherwise the dep ships in the fat jar AND is downloaded at runtime.
 * <p>
 * <strong>Kotlin Multiplatform note:</strong> kaml, kotlinx-coroutines,
 * kotlinx-serialization and other KMP libs publish a parent artifact whose
 * {@code .pom} is a Gradle Module Metadata pointer to the JVM variant. Paper's
 * {@link MavenLibraryResolver} only reads Maven POMs, so requesting the parent
 * coord yields an empty classpath entry. Always use the {@code -jvm} artifact
 * for KMP libs (already done in {@link #STANDARD_NEXUS_LIBRARIES}).
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class NexusPaperPluginLoader implements PluginLoader {

    /** Maven Central via repo1, bypassing mirror configuration on the host. */
    public static final RemoteRepository MAVEN_CENTRAL_DIRECT = new RemoteRepository.Builder(
            "central",
            "default",
            "https://repo1.maven.org/maven2/"
    ).build();

    /**
     * Standard runtime libraries that every Nexus-based plugin needs. Pinned
     * to versions known to work together; bump them deliberately.
     */
    public static final List<String> STANDARD_NEXUS_LIBRARIES = Collections.unmodifiableList(List.of(
            // Kotlin runtime
            "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
            "org.jetbrains.kotlin:kotlin-reflect:2.3.20",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",

            // Nexus stack (config + scanning + DI use these directly)
            "com.charleskorn.kaml:kaml-jvm:0.59.0",
            "io.github.classgraph:classgraph:4.8.174",

            // Logging
            "org.slf4j:slf4j-api:2.0.13"
    ));

    /**
     * Override to declare any additional Maven coordinates required by your
     * plugin (JDBC drivers, HikariCP, Vault, etc.). Default: empty.
     */
    @NotNull
    protected List<String> additionalLibraries() {
        return Collections.emptyList();
    }

    /**
     * Override to add additional Maven repositories. Default: empty. The
     * standard repo {@link #MAVEN_CENTRAL_DIRECT} is always added before this
     * runs.
     */
    @NotNull
    protected List<RemoteRepository> additionalRepositories() {
        return Collections.emptyList();
    }

    @Override
    public void classloader(@NotNull PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(MAVEN_CENTRAL_DIRECT);

        List<RemoteRepository> extraRepos = additionalRepositories();
        if (extraRepos == null) {
            throw new IllegalStateException(
                    getClass().getName() + ".additionalRepositories() returned null — return an empty list instead"
            );
        }
        for (RemoteRepository extra : extraRepos) {
            if (extra == null) {
                throw new IllegalStateException(
                        getClass().getName() + ".additionalRepositories() contained a null entry"
                );
            }
            resolver.addRepository(extra);
        }

        List<String> extraLibs = additionalLibraries();
        if (extraLibs == null) {
            throw new IllegalStateException(
                    getClass().getName() + ".additionalLibraries() returned null — return an empty list instead"
            );
        }
        List<String> coords = new ArrayList<>(STANDARD_NEXUS_LIBRARIES.size() + extraLibs.size());
        coords.addAll(STANDARD_NEXUS_LIBRARIES);
        coords.addAll(extraLibs);

        for (String coord : coords) {
            if (coord == null || coord.isBlank()) {
                throw new IllegalStateException(
                        getClass().getName() + ".additionalLibraries() contained a null/blank coordinate"
                );
            }
            resolver.addDependency(new Dependency(new DefaultArtifact(coord), null));
        }

        builder.addLibrary(resolver);
    }
}
