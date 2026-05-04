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

import dev.ikm.komet.desktop.maintenance.markdown.MarkdownPrintLayout;
import dev.ikm.komet.desktop.maintenance.markdown.MarkdownTextModel;
import dev.ikm.tinkar.provider.search.Indexer;
import dev.ikm.tinkar.provider.search.maintenance.LuceneIndexAnalyzer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import org.apache.lucene.index.IndexWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * A standalone window that analyzes a Lucene index, optionally runs a
 * recommended maintenance sequence, and renders the resulting Markdown
 * report via {@code RichTextArea} + {@link MarkdownTextModel}. Mirrors
 * {@link ChangeSetSummaryWindow} in lifecycle and toolbar.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #openForLiveIndex(IndexWriter, Path, Window, LuceneIndexAnalyzer.Options)}
 *       — operate on the running search provider's index.</li>
 *   <li>{@link #openForOfflineIndex(Path, Window, LuceneIndexAnalyzer.Options)}
 *       — open a private writer at the given path. Use only when the data
 *       store is offline (no live writer can coexist).</li>
 * </ul>
 *
 * <p>The analyzer itself ({@link LuceneIndexAnalyzer}) is UI-independent and
 * can be invoked from a Maven plugin, JBang script, or batch process — this
 * class is just the JavaFX presentation layer.
 */
public final class LuceneIndexAnalysisWindow {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneIndexAnalysisWindow.class);
    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String STYLESHEET = "/dev/ikm/komet/desktop/maintenance/markdown.css";

    private LuceneIndexAnalysisWindow() { /* static helper */ }

    /**
     * Open a window that runs the analyzer against the live search provider's
     * {@link IndexWriter}. Uses the existing writer in-place; does not close it.
     *
     * @param writer          the open writer (typically {@code Indexer.indexWriter()})
     * @param indexPath       the directory backing the writer, used for display only
     * @param owner           window to attach error alerts to (may be {@code null})
     * @param options         analyzer options; pass {@link LuceneIndexAnalyzer.Options#dryRun()}
     *                        for a safe read-only capture
     * @param rebuildCallback re-population callback, required only when
     *                        {@link LuceneIndexAnalyzer.Options#runWipeAndRebuild()} is set;
     *                        may be {@code null} otherwise
     */
    public static void openForLiveIndex(IndexWriter writer, Path indexPath, Window owner,
                                        LuceneIndexAnalyzer.Options options,
                                        LuceneIndexAnalyzer.IndexRebuildCallback rebuildCallback) {
        openInternal(indexPath, owner, options, progress ->
                LuceneIndexAnalyzer.analyze(indexPath, writer, options, rebuildCallback, progress));
    }

    /**
     * Open a window that opens its own writer at {@code indexPath}, runs the
     * analyzer, and closes the writer. Use only when no live writer is open
     * against the directory — Lucene permits one writer at a time.
     *
     * @param indexPath       the {@code lucene/} directory under the data store root
     * @param owner           window to attach error alerts to (may be {@code null})
     * @param options         analyzer options
     * @param rebuildCallback re-population callback for wipe-and-rebuild; may be {@code null}
     */
    public static void openForOfflineIndex(Path indexPath, Window owner,
                                           LuceneIndexAnalyzer.Options options,
                                           LuceneIndexAnalyzer.IndexRebuildCallback rebuildCallback) {
        openInternal(indexPath, owner, options, progress ->
                LuceneIndexAnalyzer.analyze(indexPath, options, rebuildCallback, progress));
    }

    /**
     * Convenience entry: derive the live writer from the active
     * {@link Indexer}, prompt the user to choose what to run, and open
     * the window with the matching options. Three choices are offered:
     * Dry Run (read-only), Run Maintenance (forceMergeDeletes + forceMerge),
     * Wipe &amp; Rebuild (full reindex from the entity store).
     *
     * @param dataStoreRoot   root directory of the active data store (used to
     *                        derive {@code lucene/} for display)
     * @param owner           window to attach dialogs to
     * @param rebuildCallback callback that re-indexes from the entity store after
     *                        the analyzer wipes; required for the Wipe &amp;
     *                        Rebuild path. May be {@code null}, in which case
     *                        Wipe &amp; Rebuild is disabled.
     */
    public static void openWithConfirmation(File dataStoreRoot, Window owner,
                                            LuceneIndexAnalyzer.IndexRebuildCallback rebuildCallback) {
        IndexWriter writer = Indexer.indexWriter();
        if (writer == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "The Lucene index is not currently open. Open a data store first.");
            alert.setHeaderText(null);
            alert.setTitle("Lucene index not available");
            if (owner != null) alert.initOwner(owner);
            alert.showAndWait();
            return;
        }
        Path indexPath = dataStoreRoot == null
                ? Path.of("lucene")
                : dataStoreRoot.toPath().resolve("lucene");

        // Three-way choice. ButtonData is preserved so we can route the
        // user's selection back to the correct Options factory below.
        ButtonType dryRunBt   = new ButtonType("Dry Run",          ButtonBar.ButtonData.OTHER);
        ButtonType maintainBt = new ButtonType("Run Maintenance",  ButtonBar.ButtonData.NO);
        ButtonType wipeBt     = new ButtonType("Wipe & Rebuild…",  ButtonBar.ButtonData.YES);

        StringBuilder body = new StringBuilder();
        body.append("Lucene index at\n").append(indexPath).append("\n\n")
            .append("Choose an action:\n\n")
            .append("• Dry Run — read-only analysis, no modifications.\n")
            .append("• Run Maintenance — forceMergeDeletes + forceMerge(1). ")
            .append("Rewrites all segment files; may take several minutes.\n")
            .append("• Wipe & Rebuild — empties the index and re-indexes every entity ")
            .append("from the entity store in parallel. The longest option, but the ")
            .append("only one that recovers from indexer drift or duplicate documents.");
        if (rebuildCallback == null) {
            body.append("\n\n[Wipe & Rebuild is unavailable: no rebuild callback was supplied.]");
        }

        Alert confirm;
        if (rebuildCallback == null) {
            confirm = new Alert(Alert.AlertType.CONFIRMATION, body.toString(),
                    ButtonType.CANCEL, dryRunBt, maintainBt);
        } else {
            confirm = new Alert(Alert.AlertType.CONFIRMATION, body.toString(),
                    ButtonType.CANCEL, dryRunBt, maintainBt, wipeBt);
        }
        confirm.setHeaderText(null);
        confirm.setTitle("Lucene Index Maintenance");
        if (owner != null) confirm.initOwner(owner);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty()) return;
        ButtonType bt = choice.get();
        if (bt == ButtonType.CANCEL) return;

        LuceneIndexAnalyzer.Options options;
        if (bt == wipeBt) {
            // Second confirmation for the destructive path — the rebuild
            // takes much longer and the confirmation dialog is the only
            // gate before the index is emptied.
            Alert reconfirm = new Alert(Alert.AlertType.WARNING,
                    "This will empty the Lucene index and rebuild from the entity store. "
                            + "Search will be unavailable until the rebuild completes.\n\n"
                            + "Continue?",
                    ButtonType.CANCEL, ButtonType.OK);
            reconfirm.setHeaderText("Wipe & Rebuild");
            reconfirm.setTitle("Confirm destructive operation");
            if (owner != null) reconfirm.initOwner(owner);
            Optional<ButtonType> ok = reconfirm.showAndWait();
            if (ok.isEmpty() || ok.get() != ButtonType.OK) return;
            options = LuceneIndexAnalyzer.Options.wipeAndRebuild();
        } else if (bt == maintainBt) {
            options = LuceneIndexAnalyzer.Options.recommendedSequence();
        } else {
            options = LuceneIndexAnalyzer.Options.dryRun();
        }

        openForLiveIndex(writer, indexPath, owner, options, rebuildCallback);
    }

    // -----------------------------------------------------------------
    // Private rendering shell
    // -----------------------------------------------------------------

    @FunctionalInterface
    private interface AnalysisCall {
        LuceneIndexAnalyzer.Report run(LuceneIndexAnalyzer.ProgressCallback progress) throws IOException;
    }

    private static void openInternal(Path indexPath, Window owner,
                                     LuceneIndexAnalyzer.Options options,
                                     AnalysisCall analysisCall) {
        Stage stage = new Stage();
        String pathLabel = indexPath == null ? "(in-memory)" : indexPath.getFileName().toString();
        stage.setTitle("Lucene Index Maintenance — " + pathLabel);

        ProgressIndicator progress = new ProgressIndicator();
        Label progressLabel = new Label("Analyzing index…");
        StackPane center = new StackPane(progress);
        center.setPadding(new Insets(24));

        Label status = new Label("");
        status.setWrapText(true);

        Button printButton = new Button("Print Report…");
        printButton.setDisable(true);
        Button saveButton = new Button("Save Report…");
        saveButton.setDisable(true);
        Button closeButton = new Button("Close");
        closeButton.setOnAction(_ -> stage.close());

        HBox toolbar = new HBox(8, status, spacer(), printButton, saveButton, closeButton);
        toolbar.setPadding(new Insets(8));

        BorderPane root = new BorderPane();
        HBox progressTop = new HBox(8, progressLabel);
        progressTop.setPadding(new Insets(8));
        root.setTop(progressTop);
        root.setCenter(center);
        root.setBottom(toolbar);

        Scene scene = new Scene(root, 1024, 720);
        URL css = LuceneIndexAnalysisWindow.class.getResource(STYLESHEET);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            LOG.warn("markdown.css not found on classpath at {}", STYLESHEET);
        }
        stage.setScene(scene);
        stage.show();

        Task<LuceneIndexAnalyzer.Report> task = new Task<>() {
            @Override
            protected LuceneIndexAnalyzer.Report call() throws IOException {
                LuceneIndexAnalyzer.ProgressCallback cb = (stageMessage, fraction) ->
                        Platform.runLater(() -> progressLabel.setText(stageMessage));
                return analysisCall.run(cb);
            }
        };
        task.setOnSucceeded(_ -> Platform.runLater(() -> {
            LuceneIndexAnalyzer.Report report = task.getValue();
            String markdown = report.toMarkdown();
            ZonedDateTime completedAt = ZonedDateTime.now();

            RichTextArea view = new RichTextArea(MarkdownTextModel.of(markdown));
            view.setEditable(false);
            view.setWrapText(true);
            view.getStyleClass().add("markdown-view");

            root.setTop(null);
            root.setCenter(view);

            printButton.setDisable(false);
            printButton.setOnAction(_ -> printReport(markdown, pathLabel, stage));
            saveButton.setDisable(false);
            saveButton.setOnAction(_ -> saveReport(pathLabel, markdown, completedAt, stage));

            int lines = markdown == null ? 0 : (int) markdown.lines().count();
            long reclaimed = report.totalBytesReclaimed();
            String reclaimedNote = reclaimed > 0
                    ? String.format(" — reclaimed %,d bytes", reclaimed)
                    : "";
            status.setText(String.format("Rendered %,d lines%s.", lines, reclaimedNote));
        }));
        task.setOnFailed(_ -> Platform.runLater(() -> {
            Throwable t = task.getException();
            LOG.error("Lucene index analysis failed for {}", indexPath, t);
            stage.close();
            Alert error = new Alert(Alert.AlertType.ERROR,
                    "Lucene index analysis failed: "
                            + (t == null ? "unknown error" : t.getMessage()));
            error.setHeaderText(null);
            error.setTitle("Analysis failed");
            if (owner != null) error.initOwner(owner);
            error.showAndWait();
        }));
        Thread runner = new Thread(task, "lucene-index-analyzer");
        runner.setDaemon(true);
        runner.start();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static void saveReport(String pathLabel, String markdown,
                                   ZonedDateTime completedAt, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Lucene Index Report");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        chooser.setInitialFileName("lucene-maintenance-" + pathLabel + "-"
                + completedAt.format(FILENAME_TS) + ".md");
        File destination = chooser.showSaveDialog(owner);
        if (destination == null) {
            return;
        }
        try {
            Files.writeString(destination.toPath(), markdown,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            LOG.info("Wrote Lucene maintenance report to {}", destination.getAbsolutePath());
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "Report written to " + destination.getAbsolutePath());
            info.setHeaderText(null);
            info.setTitle("Report saved");
            info.initOwner(owner);
            info.showAndWait();
        } catch (IOException e) {
            LOG.error("Failed to write report to {}", destination.getAbsolutePath(), e);
            Alert error = new Alert(Alert.AlertType.ERROR,
                    "Failed to save report: " + e.getMessage());
            error.setHeaderText(null);
            error.setTitle("Save failed");
            error.initOwner(owner);
            error.showAndWait();
        }
    }

    /**
     * Print the full report. Identical strategy to {@code ChangeSetSummaryWindow}:
     * re-render the Markdown into a non-virtualized layout so every paragraph
     * is realized, then paginate via translate-and-clip.
     */
    private static void printReport(String markdown, String pathLabel, Window owner) {
        if (markdown == null || markdown.isEmpty()) return;

        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "No printers are available.");
            alert.setHeaderText(null);
            alert.setTitle("Print");
            alert.initOwner(owner);
            alert.showAndWait();
            return;
        }
        job.getJobSettings().setJobName("Lucene Index Maintenance — " + pathLabel);

        if (!job.showPrintDialog(owner)) {
            return; // User canceled.
        }

        PageLayout pageLayout = job.getJobSettings().getPageLayout();
        double pageWidth = pageLayout.getPrintableWidth();
        double pageHeight = pageLayout.getPrintableHeight();

        VBox content = MarkdownPrintLayout.build(markdown);
        content.setPrefWidth(pageWidth);
        content.setMaxWidth(pageWidth);

        Pane pageHolder = new Pane(content);
        pageHolder.setMinSize(pageWidth, pageHeight);
        pageHolder.setMaxSize(pageWidth, pageHeight);
        pageHolder.setPrefSize(pageWidth, pageHeight);
        pageHolder.setClip(new Rectangle(pageWidth, pageHeight));

        Scene printScene = new Scene(pageHolder, pageWidth, pageHeight);
        URL css = LuceneIndexAnalysisWindow.class.getResource(STYLESHEET);
        if (css != null) {
            printScene.getStylesheets().add(css.toExternalForm());
        }
        pageHolder.applyCss();
        pageHolder.layout();

        double totalHeight = content.prefHeight(pageWidth);
        int pageCount = Math.max(1, (int) Math.ceil(totalHeight / pageHeight));

        long printStartNanos = System.nanoTime();
        boolean ok = true;
        for (int pageIndex = 0; pageIndex < pageCount && ok; pageIndex++) {
            content.setTranslateY(-pageIndex * pageHeight);
            ok = job.printPage(pageLayout, pageHolder);
            if (!ok) {
                LOG.warn("printPage failed at page {} of {}", pageIndex + 1, pageCount);
            }
        }
        long elapsedMillis = (System.nanoTime() - printStartNanos) / 1_000_000L;
        if (ok) {
            job.endJob();
            LOG.info("Printed {} page(s) for Lucene report in {} ms ({} ms/page)",
                    pageCount, elapsedMillis, pageCount == 0 ? 0 : elapsedMillis / pageCount);
        } else {
            job.cancelJob();
        }
    }
}
