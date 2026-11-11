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
package dev.ikm.komet.desktop.alerts;

import dev.ikm.komet.desktop.maintenance.markdown.MarkdownTextModel;
import dev.ikm.tinkar.common.alert.AlertLog;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import jfx.incubator.scene.control.richtext.RichTextArea;

import java.net.URL;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The Warnings and Errors list: everything in the {@link AlertLog}, newest first, as one
 * read-only rich text document, so any of it can be selected and copied. The journal's
 * sidebar shows it as a tray and the landing page and the View menu as a window. It follows
 * the log, so an alert reported while it is open appears in it.
 */
public final class WarningsAndErrorsView {

    private static final String MARKDOWN_STYLESHEET = "/dev/ikm/komet/desktop/maintenance/markdown.css";

    private final AlertLog log;
    private final VBox root = new VBox();
    private final RichTextArea text = new RichTextArea(MarkdownTextModel.of(""));
    private final Label countLabel = new Label("0");
    private final Button copy = button("Copy All", "warningsAndErrorsCopy", "Copy the whole list as text");
    private final Button clear = button("Clear…", "warningsAndErrorsClear", "Empty the list for this knowledge base");
    private final ReadOnlyIntegerWrapper unseen = new ReadOnlyIntegerWrapper();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final Runnable changed = this::queueRefresh;
    private BooleanSupplier beingRead = () -> false;
    /** What the text was last drawn from; null until the first drawing. */
    private List<AlertLog.Entry> shownEntries;

    /**
     * Builds the list and starts following the log.
     *
     * @param log the log to show
     */
    public WarningsAndErrorsView(AlertLog log) {
        this.log = log;
        root.getStyleClass().add("unpublished-changes-container");
        root.setPrefWidth(440);
        root.setId("warningsAndErrors");

        Label title = new Label("Warnings and Errors");
        title.getStyleClass().add("title-label");
        countLabel.getStyleClass().add("count-pill");
        HBox titleRow = new HBox(title, countLabel);
        titleRow.getStyleClass().add("title-row");
        Label subtitle = new Label("What Komet reported while it started, imported and worked on this knowledge base");
        subtitle.getStyleClass().add("subtitle-label");
        subtitle.setWrapText(true);
        VBox header = new VBox(titleRow, subtitle);
        header.getStyleClass().add("header");

        text.setId("warningsAndErrorsText");
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("markdown-view");
        VBox.setVgrow(text, Priority.ALWAYS);

        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(WarningsAndErrors.plainText(log.entries(), ZoneId.systemDefault()));
            Clipboard.getSystemClipboard().setContent(content);
        });
        clear.setOnAction(e -> {
            if (confirmClear()) {
                log.clear();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(copy, spacer, clear);
        buttons.getStyleClass().add("footer-buttons");
        VBox footer = new VBox(buttons);
        footer.getStyleClass().add("footer");

        root.getChildren().addAll(header, text, footer);
        URL css = WarningsAndErrorsView.class.getResource(MARKDOWN_STYLESHEET);
        if (css != null) {
            root.getStylesheets().add(css.toExternalForm());
        }
        log.addListener(changed);
        refresh();
    }

    /** @return the list's node */
    public VBox fxObject() {
        return root;
    }

    /** @return how many alerts were reported since a person last looked at the list */
    public ReadOnlyIntegerProperty unseenProperty() {
        return unseen.getReadOnlyProperty();
    }

    /**
     * Says when the list is in front of a person, so an alert that arrives then is not counted
     * as new.
     *
     * @param beingRead true while the list is on screen and in use
     */
    public void seenWhile(BooleanSupplier beingRead) {
        this.beingRead = beingRead;
    }

    /** Called as the list comes into view: nothing in it is new any more. */
    public void shown() {
        log.markSeen();
        refresh();
    }

    /** Stops following the log. */
    public void shutdown() {
        log.removeListener(changed);
    }

    /** The log changes on any thread and can change often; one redraw serves every change since the last. */
    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            Platform.runLater(this::refresh);
        }
    }

    private void refresh() {
        refreshQueued.set(false);
        if (beingRead.getAsBoolean() && log.unseenCount() > 0) {
            log.markSeen();
        }
        List<AlertLog.Entry> entries = log.entries();
        unseen.set(log.unseenCount());
        if (!entries.equals(shownEntries)) {
            shownEntries = entries;
            text.setModel(MarkdownTextModel.of(WarningsAndErrors.markdown(entries, ZoneId.systemDefault())));
            countLabel.setText(Integer.toString(entries.stream().mapToInt(AlertLog.Entry::count).sum()));
            copy.setDisable(entries.isEmpty());
            clear.setDisable(entries.isEmpty());
        }
    }

    private boolean confirmClear() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "The list kept with this knowledge base is emptied. Komet's log file is not changed.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Clear Warnings and Errors");
        confirm.setHeaderText("Clear the list of warnings and errors?");
        if (root.getScene() != null) {
            confirm.initOwner(root.getScene().getWindow());
        }
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private static Button button(String label, String id, String tip) {
        Button button = new Button(label);
        button.setId(id);
        button.getStyleClass().add("discard-all-button");
        button.setTooltip(new Tooltip(tip));
        return button;
    }
}
