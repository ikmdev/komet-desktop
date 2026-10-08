package dev.ikm.komet.desktop;

import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.common.service.ServiceLifecyclePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relaunches Komet to finish a migration from a legacy layout to a 64-bit database, once this instance's
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

    /** Folder to propose for the new 64-bit database; set when a migration requests a relaunch. */
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
        File log = relaunchLog();
        try {
            ProcessBuilder builder = new ProcessBuilder(relaunchCommand(launcher, newFolder));
            // Kept, not discarded: a relaunch that fails to start is otherwise invisible (ike-issues#1156).
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            LOG.info("Migration: database closed; relaunched Komet via {} to create {} (output in {})",
                    launcher, newFolder, log);
        } catch (IOException e) {
            LOG.warn("Migration: could not relaunch Komet via {}", launcher, e);
        }
    }

    /**
     * The relaunch command: the launcher, then the folder as one program argument.
     *
     * <p>The folder travels as {@code --migration-folder=<folder>}, never in
     * {@code JAVA_OPTS}: {@code launchKomet} splits {@code JAVA_OPTS} on whitespace
     * and chokes on quotes, so a database name with a space or an apostrophe broke
     * the relaunch. Program arguments pass through the launcher's {@code "$@"}
     * unchanged (IKE-Network/ike-issues#1156).
     *
     * @param launcher  the {@code launchKomet} script
     * @param newFolder the folder the relaunched picker proposes
     * @return the command and its arguments
     */
    static List<String> relaunchCommand(File launcher, String newFolder) {
        return List.of(launcher.getAbsolutePath(),
                "--" + LaunchOptions.Option.MIGRATION_FOLDER.argumentName() + "=" + newFolder);
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

    private static File relaunchLog() {
        return new File(new File(System.getProperty("user.home"), "Solor"), "migration-relaunch.log");
    }
}
