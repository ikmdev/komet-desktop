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
import dev.ikm.tinkar.entity.maintenance.ChangeSetSummarizer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A standalone window that renders a {@link ChangeSetSummarizer} report as
 * JavaFX nodes via the JavaFX 26 incubator {@code RichTextArea} driven by a
 * {@link MarkdownTextModel}. One window per change set; non-modal so the user
 * can compare multiple change sets side by side.
 *
 * <p>Lifecycle: {@link #openFor(File, Window)} runs the summarizer on a
 * background thread, opens an empty window with a progress indicator, and
 * swaps in the rendered Markdown when the task completes. On failure, the
 * window closes itself and shows an error alert owned by the supplied parent
 * window.
 */
public final class ChangeSetSummaryWindow {

    private static final Logger LOG = LoggerFactory.getLogger(ChangeSetSummaryWindow.class);
    private static final DateTimeFormatter FILENAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String STYLESHEET = "/dev/ikm/komet/desktop/maintenance/markdown.css";

    private ChangeSetSummaryWindow() { /* static helper */ }

    /**
     * Opens a new window summarizing the change set at the given path.
     *
     * @param changeSetZip the change set ZIP to summarize
     * @param owner        the window to attach error alerts to (may be {@code null})
     */
    public static void openFor(File changeSetZip, Window owner) {
        Stage stage = new Stage();
        stage.setTitle("Change Set Summary — " + changeSetZip.getName());

        ProgressIndicator progress = new ProgressIndicator();
        Label progressLabel = new Label("Summarizing " + changeSetZip.getName() + "…");
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

        Scene scene = new Scene(root, 960, 640);
        URL css = ChangeSetSummaryWindow.class.getResource(STYLESHEET);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        } else {
            LOG.warn("markdown.css not found on classpath at {}", STYLESHEET);
        }
        stage.setScene(scene);
        stage.show();

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws IOException {
                ChangeSetSummarizer summarizer = new ChangeSetSummarizer();
                ChangeSetSummarizer.Report report = summarizer.summarize(changeSetZip);
                return report.toMarkdown();
            }
        };
        task.setOnSucceeded(_ -> Platform.runLater(() -> {
            String markdown = task.getValue();
            ZonedDateTime completedAt = ZonedDateTime.now();

            RichTextArea view = new RichTextArea(MarkdownTextModel.of(markdown));
            view.setEditable(false);
            view.setWrapText(true);
            view.getStyleClass().add("markdown-view");

            root.setTop(null);
            root.setCenter(view);

            printButton.setDisable(false);
            printButton.setOnAction(_ -> printReport(markdown, changeSetZip, stage));
            saveButton.setDisable(false);
            saveButton.setOnAction(_ -> saveReport(changeSetZip, markdown, completedAt, stage));

            int lines = markdown == null ? 0 : (int) markdown.lines().count();
            status.setText(String.format("Rendered %,d lines of Markdown.", lines));
        }));
        task.setOnFailed(_ -> Platform.runLater(() -> {
            Throwable t = task.getException();
            LOG.error("Change set summarizer failed for {}", changeSetZip.getAbsolutePath(), t);
            stage.close();
            Alert error = new Alert(Alert.AlertType.ERROR,
                    "Failed to summarize " + changeSetZip.getName() + ": "
                            + (t == null ? "unknown error" : t.getMessage()));
            error.setHeaderText(null);
            error.setTitle("Summary failed");
            if (owner != null) error.initOwner(owner);
            error.showAndWait();
        }));
        Thread runner = new Thread(task, "change-set-summarizer");
        runner.setDaemon(true);
        runner.start();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static void saveReport(File source, String markdown, ZonedDateTime completedAt, Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Change Set Summary");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        String stem = stripExtension(source.getName());
        chooser.setInitialFileName(stem + "-summary-" + completedAt.format(FILENAME_TS) + ".md");
        File destination = chooser.showSaveDialog(owner);
        if (destination == null) {
            return;
        }
        try {
            Files.writeString(destination.toPath(), markdown,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            LOG.info("Wrote change set summary to {}", destination.getAbsolutePath());
            Alert info = new Alert(Alert.AlertType.INFORMATION,
                    "Report written to " + destination.getAbsolutePath());
            info.setHeaderText(null);
            info.setTitle("Report saved");
            info.initOwner(owner);
            info.showAndWait();
        } catch (IOException e) {
            LOG.error("Failed to write summary to {}", destination.getAbsolutePath(), e);
            Alert error = new Alert(Alert.AlertType.ERROR,
                    "Failed to save report: " + e.getMessage());
            error.setHeaderText(null);
            error.setTitle("Save failed");
            error.initOwner(owner);
            error.showAndWait();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /**
     * Print the full report by re-rendering the Markdown source into a
     * non-virtualized layout via {@link MarkdownPrintLayout} and paginating
     * it across pages with translate-and-clip.
     *
     * <p>The on-screen view uses {@code RichTextArea} which virtualizes
     * paragraphs — only the viewport is realized, so {@code printPage(view)}
     * would lose anything scrolled off-screen. Instead we build a fresh
     * fully-laid-out tree from the same Markdown source, attach it to a
     * print-only Scene that loads the same {@code markdown.css} stylesheet,
     * and slice it into page-sized chunks.
     */
    private static void printReport(String markdown, File source, Window owner) {
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
        job.getJobSettings().setJobName("Change Set Summary — " + source.getName());

        if (!job.showPrintDialog(owner)) {
            // User canceled.
            return;
        }

        PageLayout pageLayout = job.getJobSettings().getPageLayout();
        double pageWidth = pageLayout.getPrintableWidth();
        double pageHeight = pageLayout.getPrintableHeight();

        // Build the document. The print layout is a flat VBox of TextFlow /
        // Label / GridPane children — no virtualization, so every paragraph
        // is laid out and measurable.
        VBox content = MarkdownPrintLayout.build(markdown);
        content.setPrefWidth(pageWidth);
        content.setMaxWidth(pageWidth);

        // Single-page Pane that holds the full content. Translating content
        // upward between pages exposes the next slice; the clip keeps each
        // page from bleeding into the next.
        Pane pageHolder = new Pane(content);
        pageHolder.setMinSize(pageWidth, pageHeight);
        pageHolder.setMaxSize(pageWidth, pageHeight);
        pageHolder.setPrefSize(pageWidth, pageHeight);
        pageHolder.setClip(new Rectangle(pageWidth, pageHeight));

        Scene printScene = new Scene(pageHolder, pageWidth, pageHeight);
        URL css = ChangeSetSummaryWindow.class.getResource(STYLESHEET);
        if (css != null) {
            printScene.getStylesheets().add(css.toExternalForm());
        }
        // Single layout pass before the print loop. setTranslateY is a transform,
        // not a layout property — translating between pages doesn't invalidate
        // layout, so per-page applyCss/layout would be O(pages × content) for
        // no benefit. Keep it O(content) by laying out once.
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
            LOG.info("Printed {} page(s) for {} in {} ms ({} ms/page)",
                    pageCount, source.getName(), elapsedMillis,
                    pageCount == 0 ? 0 : elapsedMillis / pageCount);
        } else {
            job.cancelJob();
        }
    }
}
