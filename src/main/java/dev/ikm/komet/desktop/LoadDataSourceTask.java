/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.komet.desktop;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Alert;
import dev.ikm.tinkar.common.service.DataStoreAlreadyOpenException;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;
import dev.ikm.tinkar.common.service.TrackingCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class LoadDataSourceTask extends TrackingCallable<Void> {
    private static final Logger LOG = LoggerFactory.getLogger(LoadDataSourceTask.class);
    final SimpleObjectProperty<AppState> state;

    public LoadDataSourceTask(SimpleObjectProperty<AppState> state) {
        super(false, true);
        this.state = state;
        updateTitle("Loading Data Source");
        updateMessage("Executing data source...");
        updateProgress(-1, -1);
    }

    /** Each step of the startup, as the outer bar's message and progress. */
    private final ServiceLifecycleManager.StartupListener steps = step -> {
        updateMessage(step.phaseLabel() + ": starting " + step.serviceName()
                + " (step " + (step.serviceIndex() + 1) + " of " + step.serviceCount()
                + "; " + step.completed() + " complete, " + step.remaining() + " remaining)");
        updateProgress(step.serviceIndex(), step.serviceCount());
    };

    @Override
    protected Void compute() throws Exception {
        try {
            LOG.info("LoadDataSourceTask starting...");
            // The outer bar follows the lifecycle's steps; the import and the index rebuild
            // within them are tracking callables of their own, with their own rows.
            ServiceLifecycleManager.get().addStartupListener(steps);
            try {
                PrimitiveData.start();
            } finally {
                ServiceLifecycleManager.get().removeStartupListener(steps);
            }
            updateMessage("Data source loaded");
            updateProgress(1, 1);
            LOG.info("PrimitiveData.start() completed successfully");
            LOG.info("Scheduling state transition to SELECT_USER");
            Platform.runLater(() -> {
                // A legacy-layout database (6-bit or 8-bit) opens normally; warn before any
                // work and offer legacy mode or migration to a 64-bit database
                // (IKE-Network/ike-issues#1138, #1258).
                if (NidLayoutMigration.inLegacyMode()) {
                    switch (NidLayoutMigration.askAfterOpen()) {
                        case OPEN_IN_LEGACY_MODE -> LOG.info("User chose to continue in {} mode", NidLayoutMigration.layoutName());
                        case MIGRATE -> {
                            NidLayoutMigration.migrate(
                                    () -> state.set(AppState.SHUTDOWN),
                                    () -> state.set(AppState.SELECT_USER));
                            return;
                        }
                        case QUIT -> {
                            state.set(AppState.SHUTDOWN);
                            return;
                        }
                    }
                }
                LOG.info("Platform.runLater executing - setting state to SELECT_USER");
                state.set(AppState.SELECT_USER);
                LOG.info("State set to SELECT_USER");
            });
            LOG.info("LoadDataSourceTask completed successfully");
            return null;
        } catch (Throwable ex) {
            LOG.error("LoadDataSourceTask failed with exception", ex);
            // A data store that is already open in another process is a terminal,
            // non-retryable condition. Rather than silently leaving the app wedged
            // in LOADING_DATA_SOURCE (the old behavior), tell the user plainly and
            // exit gracefully.
            Optional<DataStoreAlreadyOpenException> alreadyOpen = DataStoreAlreadyOpenException.findIn(ex);
            if (alreadyOpen.isPresent()) {
                handleDataStoreAlreadyOpen(alreadyOpen.get());
            }
            return null;
        } finally {
            updateTitle("Data source loaded in " + durationString());
            updateMessage("Completed");
        }
    }

    /**
     * Presents a clear, non-technical message that the selected database is
     * already open elsewhere, then transitions the application to
     * {@link AppState#SHUTDOWN} for a graceful exit.
     * <p>The dialog and the subsequent shutdown are scheduled in a single
     * JavaFX runnable so the state transition happens only after the user
     * dismisses the dialog ({@link Alert#showAndWait()} blocks until then),
     * rather than racing the quit sequence.
     *
     * @param alreadyOpen the terminal failure describing which data store is in use
     */
    private void handleDataStoreAlreadyOpen(DataStoreAlreadyOpenException alreadyOpen) {
        LOG.error("Data store already open in another process: {}", alreadyOpen.dataStorePath());
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Database already open");
            alert.setHeaderText("This database is already open in another Komet process");
            alert.setContentText(
                    alreadyOpen.dataStorePath()
                            + "\n\nClose the other Komet window (or process) using this database, "
                            + "then start Komet again.");
            alert.showAndWait();
            state.set(AppState.SHUTDOWN);
        });
    }
}
