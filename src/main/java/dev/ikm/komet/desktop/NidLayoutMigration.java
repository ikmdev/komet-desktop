package dev.ikm.komet.desktop;

import dev.ikm.komet.framework.progress.ProgressHelper;
import dev.ikm.tinkar.common.id.impl.NidLayout;
import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.DataServiceProperty;
import dev.ikm.tinkar.common.service.DataUriOption;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Handling for a knowledge base opened in the legacy 6-bit nid layout
 * (IKE-Network/ike-issues#1138): the warning shown after it opens, the persistent
 * "6-bit mode" indicator, and the one-step migration to a new 8-bit database.
 *
 * <p>Migration exports the open database to a protobuf file, then shuts Komet
 * down; once the database is closed, {@link MigrationRelaunchLifecycle} starts a
 * new Komet with the data-source picker set to create a new Rocks knowledge base
 * from that export. A second process is required because the Rocks provider is a
 * one-per-process singleton; the new database is created empty, so it is 8-bit.
 */
final class NidLayoutMigration {
    private static final Logger LOG = LoggerFactory.getLogger(NidLayoutMigration.class);

    /** Controller name of the provider that creates a Rocks knowledge base from an export. */
    private static final String NEW_ROCKS_CONTROLLER = "New Rocks KB";

    /** Property name of that controller's new-folder field. */
    private static final String NEW_FOLDER_PROPERTY_NAME = "New folder name";

    /** What the user chose in the 6-bit warning. */
    enum Choice {
        /** Keep working in 6-bit mode. */
        OPEN_IN_SIX_BIT_MODE,
        /** Export, then create a new 8-bit database from the export. */
        MIGRATE,
        /** Quit Komet. */
        QUIT
    }

    private NidLayoutMigration() {
    }

    /**
     * Returns {@code true} if the open knowledge base uses the 6-bit layout.
     *
     * @return whether Komet is in 6-bit mode
     */
    static boolean inSixBitMode() {
        return NidLayout.active() == NidLayout.SIX_BIT;
    }

    /**
     * Suffix for titles while in 6-bit mode — the persistent indicator.
     *
     * @return {@code " — 6-bit database (migrate to 8-bit)"} in 6-bit mode, otherwise empty
     */
    static String titleSuffix() {
        return inSixBitMode() ? " — 6-bit database (migrate to 8-bit)" : "";
    }

    /**
     * Shows the 6-bit warning and waits for the user's choice. Must run on the
     * JavaFX application thread.
     *
     * @return the choice; {@link Choice#QUIT} if the dialog is closed
     */
    static Choice askAfterOpen() {
        ButtonType openButton = new ButtonType("Open in 6-bit mode", ButtonBar.ButtonData.OK_DONE);
        ButtonType migrateButton = new ButtonType("Migrate to a new 8-bit database", ButtonBar.ButtonData.OTHER);
        ButtonType quitButton = new ButtonType("Quit", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", openButton, migrateButton, quitButton);
        alert.setTitle("6-bit database");
        alert.setHeaderText("This database uses the older 6-bit format");
        alert.setContentText(databaseName()
                + "\n\nThe 6-bit format allows at most " + NidLayout.SIX_BIT.patternPatternSequence()
                + " patterns; the current format allows up to " + NidLayout.EIGHT_BIT.patternPatternSequence()
                + ". You can keep working in 6-bit mode — browse, edit, and export as before — "
                + "within the 63-pattern limit."
                + "\n\nMigrate exports this database and creates a new 8-bit database from the export. "
                + "Migration does not change this database; it stays in the 6-bit format.");
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == quitButton) {
            return Choice.QUIT;
        }
        return result.get() == migrateButton ? Choice.MIGRATE : Choice.OPEN_IN_SIX_BIT_MODE;
    }

    /**
     * Exports the open 6-bit database, sets up the picker to create the 8-bit
     * database from the export, and relaunches Komet. {@code onExported} runs on
     * the JavaFX thread once the export succeeds (the caller shuts this instance
     * down); on failure the user is told and {@code onFailed} runs instead.
     *
     * @param onExported called after a successful export and relaunch attempt
     * @param onFailed   called if the export fails
     */
    static void migrate(Runnable onExported, Runnable onFailed) {
        File solor = new File(System.getProperty("user.home"), "Solor");
        String name = databaseName();
        File exportFile = firstAvailable(solor, name + "-migration", "-pb.zip");
        String newFolder = firstAvailable(solor, name + "-8bit", "").getName();
        LOG.info("Migrating 6-bit database {} — exporting to {}, new 8-bit folder {}", name, exportFile, newFolder);

        CompletableFuture<?> export = ProgressHelper.progress(
                new ExportEntitiesToProtobufFile(exportFile), "Cancel Migration");
        export.whenComplete((summary, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                LOG.error("Migration export of {} failed", name, failure);
                exportFile.delete();
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Migration failed");
                alert.setHeaderText("The database could not be exported");
                alert.setContentText(failure.getMessage()
                        + "\n\nThe 6-bit database was not migrated and stays open in 6-bit mode.");
                alert.showAndWait();
                onFailed.run();
                return;
            }
            LOG.info("Migration export complete: {} ({})", exportFile, summary);
            rememberNewDatabaseSelection(exportFile);
            boolean relaunched = MigrationRelaunchLifecycle.canRelaunch();
            if (relaunched) {
                // Performed at shutdown, after the database closes (MigrationRelaunchLifecycle).
                MigrationRelaunchLifecycle.requestRelaunch(newFolder);
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Migration");
            alert.setHeaderText("Export complete");
            alert.setContentText("Exported to " + exportFile.getName() + ".\n\n"
                    + (relaunched
                        ? "Komet will now close this database and reopen with “" + NEW_ROCKS_CONTROLLER + "”, the export, "
                          + "and the folder “" + newFolder + "” selected. Press OK there to create the 8-bit database."
                        : "Start Komet again: “" + NEW_ROCKS_CONTROLLER + "” and the export are pre-selected. "
                          + "Enter a folder name such as “" + newFolder + "” and press OK.")
                    + "\n\nThe 6-bit database was not changed; it stays in the 6-bit format.");
            alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
            alert.showAndWait();
            onExported.run();
        }));
    }

    /**
     * On the relaunch that completes a migration, puts the proposed folder name on
     * the "New Rocks KB" controller so the picker shows it. Call before the picker
     * is created; a no-op on an ordinary launch.
     */
    static void applyPendingMigration() {
        Optional<String> pending = LaunchOptions.current().get(LaunchOptions.Option.MIGRATION_FOLDER);
        if (pending.isEmpty()) {
            return;
        }
        String folder = pending.get();
        newRocksController().ifPresentOrElse(controller -> controller.providerProperties()
                        .forEachKey((DataServiceProperty key) -> {
                            if (NEW_FOLDER_PROPERTY_NAME.equals(key.propertyName())) {
                                controller.setDataServiceProperty(key, folder);
                                LOG.info("Migration: proposing new 8-bit folder {}", folder);
                            }
                        }),
                () -> LOG.warn("Migration: no '{}' controller to propose folder {}", NEW_ROCKS_CONTROLLER, folder));
    }

    private static void rememberNewDatabaseSelection(File exportFile) {
        newRocksController().ifPresent(controller -> SelectDataSourceController.persistSelection(
                controller, new DataUriOption(exportFile.getName(), exportFile.toURI())));
    }

    private static Optional<DataServiceController<?>> newRocksController() {
        return PrimitiveData.getControllerOptions().stream()
                .filter(controller -> NEW_ROCKS_CONTROLLER.equals(controller.controllerName()))
                .findFirst();
    }

    private static String databaseName() {
        return ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT)
                .map(root -> ((File) root).getName())
                .orElseGet(() -> PrimitiveData.get().name());
    }

    private static File firstAvailable(File folder, String base, String suffix) {
        File candidate = new File(folder, base + suffix);
        for (int i = 2; candidate.exists(); i++) {
            candidate = new File(folder, base + "-" + i + suffix);
        }
        return candidate;
    }
}
