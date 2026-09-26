package dev.ikm.komet.desktop;

import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.common.service.ServiceLifecyclePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relaunches Komet to finish a 6-bit → 8-bit migration, once this instance's
 * knowledge base is closed (IKE-Network/ike-issues#1138).
 *
 * <p>The service lifecycle shuts services down in reverse phase order, so this
 * service — {@link ServiceLifecyclePhase#INFRASTRUCTURE}, sub-priority 0 — shuts
 * down last, after the data provider has closed the database. The relaunch
 * happens in {@link #shutdown()}, so the new Komet never starts while the old
 * database is still open.
 */
public class MigrationRelaunchLifecycle implements ServiceLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(MigrationRelaunchLifecycle.class);

    /** Folder to propose for the new 8-bit database; set when a migration requests a relaunch. */
    private static final AtomicReference<String> PENDING_FOLDER = new AtomicReference<>();

    /** Public no-argument constructor, for service loading. */
    public MigrationRelaunchLifecycle() {
    }

    /**
     * Returns {@code true} if Komet runs from its packaged runtime image, so it can
     * relaunch itself; a development run cannot.
     *
     * @return whether a relaunch is possible
     */
    static boolean canRelaunch() {
        return launcher().canExecute();
    }

    /**
     * Requests a relaunch at shutdown, proposing {@code newFolder} for the new database.
     *
     * @param newFolder the folder the relaunched picker proposes
     */
    static void requestRelaunch(String newFolder) {
        PENDING_FOLDER.set(newFolder);
    }

    @Override
    public void startup() {
        // Nothing to start: this service exists for its place in the shutdown order.
    }

    @Override
    public void shutdown() {
        String newFolder = PENDING_FOLDER.getAndSet(null);
        if (newFolder == null) {
            return;
        }
        File launcher = launcher();
        try {
            ProcessBuilder builder = new ProcessBuilder(launcher.getAbsolutePath());
            String javaOpts = Optional.ofNullable(System.getenv("JAVA_OPTS")).orElse("");
            builder.environment().put("JAVA_OPTS",
                    (javaOpts + " -D" + NidLayoutMigration.MIGRATION_FOLDER_PROPERTY + "=" + newFolder).strip());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            LOG.info("Migration: database closed; relaunched Komet via {} to create {}", launcher, newFolder);
        } catch (IOException e) {
            LOG.warn("Migration: could not relaunch Komet via {}", launcher, e);
        }
    }

    @Override
    public ServiceLifecyclePhase getLifecyclePhase() {
        return ServiceLifecyclePhase.INFRASTRUCTURE;
    }

    @Override
    public int getSubPriority() {
        return 0;
    }

    private static File launcher() {
        return new File(System.getProperty("java.home"), "bin/launchKomet");
    }
}
