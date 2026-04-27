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
package dev.ikm.komet.desktop.maintenance;

import dev.ikm.komet.framework.view.ObservableView;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.maintenance.SingleSemanticDuplicateWithdrawer;
import dev.ikm.tinkar.entity.maintenance.SingleSemanticPatterns;
import dev.ikm.tinkar.entity.transaction.Transaction;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dev.ikm.komet.kview.fxutils.FXUtils.getFocusedWindow;

/**
 * Komet menu command — scans the configured single-semantic patterns for
 * duplicate semantics, reports the result, and (on confirmation) writes
 * {@code WITHDRAWN} versions for non-canonical duplicates using the active
 * view's author.
 *
 * <p>First open runs in dry-run mode so nothing is written until the user
 * presses the "Withdraw Duplicates" button. The withdrawal pass uses a fresh
 * {@link Transaction} that is committed on success.
 */
public final class DuplicateSemanticWithdrawerDialog extends Dialog<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(DuplicateSemanticWithdrawerDialog.class);

    private static final ButtonType WITHDRAW_BUTTON = new ButtonType("Withdraw Duplicates", ButtonBar.ButtonData.APPLY);

    private final ObservableView observableView;
    private final Label statusLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final TableView<SingleSemanticDuplicateWithdrawer.PatternResult> resultsTable = new TableView<>();

    public DuplicateSemanticWithdrawerDialog(ObservableView observableView) {
        this.observableView = observableView;

        setTitle("Single-Semantic Duplicate Withdrawer");
        initOwner(getFocusedWindow());
        initModality(Modality.APPLICATION_MODAL);
        setResizable(true);

        getDialogPane().setHeaderText(
                "Scans the configured single-semantic patterns for duplicate semantics. "
                        + "First pass is dry-run; press Withdraw Duplicates to write WITHDRAWN versions.");
        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().setAll(WITHDRAW_BUTTON, ButtonType.CLOSE);

        Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
        withdrawButton.setDisable(true);
        withdrawButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runWithdrawPass();
        });

        setResultConverter(buttonType -> null);
        runScan(true);
    }

    private VBox buildContent() {
        progress.setPrefSize(20, 20);
        progress.setVisible(false);
        HBox statusRow = new HBox(8, progress, statusLabel);
        statusRow.setPadding(new Insets(0, 0, 8, 0));

        configureResultsTable();
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        VBox content = new VBox(statusRow, resultsTable);
        content.setPadding(new Insets(8));
        content.setPrefSize(700, 320);
        return content;
    }

    private void configureResultsTable() {
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, String> patternCol = new TableColumn<>("Pattern");
        patternCol.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(PrimitiveData.text(cell.getValue().patternNid())));
        patternCol.setPrefWidth(260);

        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> scannedCol = column("Scanned",
                SingleSemanticDuplicateWithdrawer.PatternResult::componentsScanned);
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> dupCol = column("Components w/ Duplicates",
                SingleSemanticDuplicateWithdrawer.PatternResult::componentsWithDuplicates);
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> withdrawnCol = column("Withdrawn",
                SingleSemanticDuplicateWithdrawer.PatternResult::duplicatesWithdrawn);
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> alreadyCol = column("Already Withdrawn",
                SingleSemanticDuplicateWithdrawer.PatternResult::alreadyWithdrawn);
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> noCanonicalCol = column("No Canonical",
                SingleSemanticDuplicateWithdrawer.PatternResult::noCanonicalMatch);
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> wrongCol = column("Wrong Pattern",
                SingleSemanticDuplicateWithdrawer.PatternResult::wrongPatternSkipped);

        resultsTable.getColumns().setAll(patternCol, scannedCol, dupCol, withdrawnCol, alreadyCol, noCanonicalCol, wrongCol);
        resultsTable.setPlaceholder(new Label("No scan results yet."));
    }

    private static TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> column(
            String title, java.util.function.ToIntFunction<SingleSemanticDuplicateWithdrawer.PatternResult> getter) {
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(getter.applyAsInt(cell.getValue())));
        col.setPrefWidth(120);
        return col;
    }

    private void runScan(boolean dryRun) {
        Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
        withdrawButton.setDisable(true);
        progress.setVisible(true);
        statusLabel.setText(dryRun ? "Scanning…" : "Withdrawing duplicates…");

        Task<SingleSemanticDuplicateWithdrawer.Report> task = new Task<>() {
            @Override
            protected SingleSemanticDuplicateWithdrawer.Report call() {
                Transaction transaction = dryRun
                        ? null
                        : Transaction.make("Single-semantic duplicate withdrawer (Komet menu)");
                int authorNid = observableView.editCoordinate().getAuthorNidForChanges();
                SingleSemanticDuplicateWithdrawer withdrawer =
                        new SingleSemanticDuplicateWithdrawer(transaction, authorNid, dryRun);
                SingleSemanticDuplicateWithdrawer.Report report = withdrawer.scan(SingleSemanticPatterns.DEFAULT);
                if (!dryRun && transaction != null) {
                    transaction.commit();
                }
                return report;
            }
        };
        task.setOnSucceeded(_ -> Platform.runLater(() -> displayReport(task.getValue(), dryRun)));
        task.setOnFailed(_ -> Platform.runLater(() -> {
            progress.setVisible(false);
            Throwable t = task.getException();
            LOG.error("Single-semantic duplicate withdrawer failed", t);
            statusLabel.setText("Failed: " + (t == null ? "unknown error" : t.getMessage()));
        }));
        Thread runner = new Thread(task, "duplicate-semantic-withdrawer");
        runner.setDaemon(true);
        runner.start();
    }

    private void displayReport(SingleSemanticDuplicateWithdrawer.Report report, boolean dryRun) {
        progress.setVisible(false);
        resultsTable.getItems().setAll(report.perPattern().castToList());

        long components = report.totalComponentsWithDuplicates();
        long withdrawn = report.totalDuplicatesWithdrawn();
        if (dryRun) {
            statusLabel.setText(String.format(
                    "Dry run complete. Components with duplicates: %,d. Pending withdrawals: %,d.",
                    components, withdrawn));
            Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
            withdrawButton.setDisable(withdrawn == 0);
        } else {
            statusLabel.setText(String.format(
                    "Withdraw complete. Components with duplicates: %,d. Withdrawn: %,d.",
                    components, withdrawn));
            Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
            withdrawButton.setDisable(true);
        }
    }

    private void runWithdrawPass() {
        runScan(false);
    }
}
