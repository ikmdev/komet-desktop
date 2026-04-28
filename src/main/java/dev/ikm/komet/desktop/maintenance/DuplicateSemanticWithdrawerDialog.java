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
import dev.ikm.tinkar.terms.EntityProxy;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import static dev.ikm.komet.kview.fxutils.FXUtils.getFocusedWindow;

/**
 * Komet menu command — scans the configured single-semantic patterns for
 * duplicate semantics, reports the result with a determinate progress bar,
 * and (on confirmation) writes {@code WITHDRAWN} versions for non-canonical
 * duplicates using the active view's author.
 *
 * <p>First open runs in dry-run mode so nothing is written until the user
 * presses the "Withdraw Duplicates" button. The withdrawal pass uses a fresh
 * {@link Transaction} that is committed on success. After any run, the
 * "Save Report…" button writes a Markdown summary to a user-chosen file.
 */
public final class DuplicateSemanticWithdrawerDialog extends Dialog<Void> {

    private static final Logger LOG = LoggerFactory.getLogger(DuplicateSemanticWithdrawerDialog.class);
    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final ButtonType WITHDRAW_BUTTON = new ButtonType("Withdraw Duplicates", ButtonBar.ButtonData.APPLY);
    private static final ButtonType SAVE_REPORT_BUTTON = new ButtonType("Save Report…", ButtonBar.ButtonData.LEFT);

    private final ObservableView observableView;
    private final Label statusLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TableView<SingleSemanticDuplicateWithdrawer.PatternResult> resultsTable = new TableView<>();

    private SingleSemanticDuplicateWithdrawer.Report lastReport;
    private ZonedDateTime lastRunCompletedAt;
    private boolean lastRunWasDryRun = true;

    public DuplicateSemanticWithdrawerDialog(ObservableView observableView) {
        this.observableView = observableView;

        setTitle("Single-Semantic Duplicate Withdrawer");
        initOwner(getFocusedWindow());
        initModality(Modality.APPLICATION_MODAL);
        setResizable(true);

        getDialogPane().setHeaderText(
                "Scans the configured single-semantic patterns for duplicate and null semantics. "
                        + "First pass is dry-run; press Withdraw Duplicates to write WITHDRAWN versions.");
        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().setAll(SAVE_REPORT_BUTTON, WITHDRAW_BUTTON, ButtonType.CLOSE);

        Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
        withdrawButton.setDisable(true);
        withdrawButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            runScan(false);
        });

        Button saveReportButton = (Button) getDialogPane().lookupButton(SAVE_REPORT_BUTTON);
        saveReportButton.setDisable(true);
        saveReportButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            saveReport();
        });

        setResultConverter(buttonType -> null);
        runScan(true);
    }

    private VBox buildContent() {
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(14);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        HBox progressRow = new HBox(8, progressBar);
        progressRow.setPadding(new Insets(0, 0, 4, 0));

        statusLabel.setWrapText(true);

        configureResultsTable();
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        VBox content = new VBox(progressRow, statusLabel, resultsTable);
        content.setSpacing(6);
        content.setPadding(new Insets(8));
        content.setPrefSize(820, 360);
        return content;
    }

    private void configureResultsTable() {
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, String> patternCol = new TableColumn<>("Pattern");
        patternCol.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(PrimitiveData.text(cell.getValue().patternNid())));
        patternCol.setPrefWidth(240);

        resultsTable.getColumns().setAll(
                patternCol,
                column("Scanned", SingleSemanticDuplicateWithdrawer.PatternResult::componentsScanned),
                column("With Duplicates", SingleSemanticDuplicateWithdrawer.PatternResult::componentsWithDuplicates),
                column("Withdrawn", SingleSemanticDuplicateWithdrawer.PatternResult::duplicatesWithdrawn),
                column("Already Withdrawn", SingleSemanticDuplicateWithdrawer.PatternResult::alreadyWithdrawn),
                column("No Canonical", SingleSemanticDuplicateWithdrawer.PatternResult::noCanonicalMatch),
                column("Wrong Pattern", SingleSemanticDuplicateWithdrawer.PatternResult::wrongPatternSkipped),
                column("Null Nids", SingleSemanticDuplicateWithdrawer.PatternResult::nullSemanticNids));
        resultsTable.setPlaceholder(new Label("No scan results yet."));
    }

    private static TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> column(
            String title, java.util.function.ToIntFunction<SingleSemanticDuplicateWithdrawer.PatternResult> getter) {
        TableColumn<SingleSemanticDuplicateWithdrawer.PatternResult, Number> col = new TableColumn<>(title);
        col.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(getter.applyAsInt(cell.getValue())));
        col.setPrefWidth(110);
        return col;
    }

    private void runScan(boolean dryRun) {
        Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
        Button saveReportButton = (Button) getDialogPane().lookupButton(SAVE_REPORT_BUTTON);
        withdrawButton.setDisable(true);
        saveReportButton.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        statusLabel.setText(dryRun ? "Enumerating semantic nids…" : "Withdrawing duplicates…");

        AtomicLong totalRef = new AtomicLong();
        ZonedDateTime startedAt = ZonedDateTime.now();

        SingleSemanticDuplicateWithdrawer.ProgressListener listener =
                new SingleSemanticDuplicateWithdrawer.ProgressListener() {
                    @Override
                    public void onTotalNids(long total) {
                        totalRef.set(total);
                        Platform.runLater(() -> {
                            if (total <= 0) {
                                progressBar.setProgress(1.0);
                                statusLabel.setText(dryRun ? "Nothing to scan." : "Nothing to withdraw.");
                            } else {
                                progressBar.setProgress(0);
                                statusLabel.setText(String.format(
                                        dryRun ? "Scanning %,d nids…" : "Processing %,d nids…",
                                        total));
                            }
                        });
                    }

                    @Override
                    public void onNidsProcessed(long processed) {
                        long total = totalRef.get();
                        if (total <= 0) return;
                        double fraction = Math.min(1.0, processed / (double) total);
                        Platform.runLater(() -> {
                            progressBar.setProgress(fraction);
                            statusLabel.setText(String.format(
                                    "%s %,d / %,d nids (%.1f%%)",
                                    dryRun ? "Scanning" : "Processing",
                                    processed, total, fraction * 100.0));
                        });
                    }

                    @Override
                    public void onPatternStarting(EntityProxy.Pattern pattern, int nidsForPattern) {
                        LOG.info("Starting pattern {} with {} nids", pattern.description(), nidsForPattern);
                    }
                };

        Task<SingleSemanticDuplicateWithdrawer.Report> task = new Task<>() {
            @Override
            protected SingleSemanticDuplicateWithdrawer.Report call() {
                Transaction transaction = dryRun
                        ? null
                        : Transaction.make("Single-semantic duplicate withdrawer (Komet menu)");
                int authorNid = observableView.editCoordinate().getAuthorNidForChanges();
                SingleSemanticDuplicateWithdrawer withdrawer =
                        new SingleSemanticDuplicateWithdrawer(transaction, authorNid, dryRun);
                SingleSemanticDuplicateWithdrawer.Report report =
                        withdrawer.scan(SingleSemanticPatterns.DEFAULT, listener);
                if (!dryRun && transaction != null) {
                    transaction.commit();
                }
                return report;
            }
        };
        task.setOnSucceeded(_ -> Platform.runLater(() -> displayReport(task.getValue(), dryRun, startedAt)));
        task.setOnFailed(_ -> Platform.runLater(() -> {
            progressBar.setProgress(0);
            Throwable t = task.getException();
            LOG.error("Single-semantic duplicate withdrawer failed", t);
            statusLabel.setText("Failed: " + (t == null ? "unknown error" : t.getMessage()));
        }));
        Thread runner = new Thread(task, "duplicate-semantic-withdrawer");
        runner.setDaemon(true);
        runner.start();
    }

    private void displayReport(SingleSemanticDuplicateWithdrawer.Report report, boolean dryRun, ZonedDateTime startedAt) {
        progressBar.setProgress(1.0);
        resultsTable.getItems().setAll(report.perPattern().castToList());

        lastReport = report;
        lastRunCompletedAt = ZonedDateTime.now();
        lastRunWasDryRun = dryRun;

        long components = report.totalComponentsWithDuplicates();
        long withdrawn = report.totalDuplicatesWithdrawn();
        long nulls = report.totalNullSemanticNids();
        long elapsedSec = Math.max(0, lastRunCompletedAt.toEpochSecond() - startedAt.toEpochSecond());

        Button withdrawButton = (Button) getDialogPane().lookupButton(WITHDRAW_BUTTON);
        Button saveReportButton = (Button) getDialogPane().lookupButton(SAVE_REPORT_BUTTON);
        saveReportButton.setDisable(false);

        if (dryRun) {
            statusLabel.setText(String.format(
                    "Dry run complete in %ds. Components with duplicates: %,d. Pending withdrawals: %,d. Null nids: %,d.",
                    elapsedSec, components, withdrawn, nulls));
            withdrawButton.setDisable(withdrawn == 0);
        } else {
            statusLabel.setText(String.format(
                    "Withdraw complete in %ds. Components with duplicates: %,d. Withdrawn: %,d. Null nids: %,d.",
                    elapsedSec, components, withdrawn, nulls));
            withdrawButton.setDisable(true);
        }
    }

    private void saveReport() {
        if (lastReport == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Withdrawer Report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        String suggested = "duplicate-withdrawer-report-" + lastRunCompletedAt.format(FILENAME_TS) + ".md";
        chooser.setInitialFileName(suggested);
        File destination = chooser.showSaveDialog(getOwner());
        if (destination == null) {
            return;
        }
        int authorNid = observableView.editCoordinate().getAuthorNidForChanges();
        String markdown = lastReport.toMarkdown(lastRunCompletedAt, lastRunWasDryRun, authorNid);
        try {
            Files.writeString(destination.toPath(), markdown,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            LOG.info("Wrote withdrawer report to {}", destination.getAbsolutePath());
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "Report written to " + destination.getAbsolutePath());
            info.setHeaderText(null);
            info.setTitle("Report saved");
            info.initOwner(getOwner());
            info.showAndWait();
        } catch (IOException e) {
            LOG.error("Failed to write report to {}", destination.getAbsolutePath(), e);
            Alert error = new Alert(Alert.AlertType.ERROR,
                    "Failed to save report: " + e.getMessage());
            error.setHeaderText(null);
            error.setTitle("Save failed");
            error.initOwner(getOwner());
            error.showAndWait();
        }
    }
}
