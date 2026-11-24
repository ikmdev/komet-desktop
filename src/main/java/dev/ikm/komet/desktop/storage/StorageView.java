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
package dev.ikm.komet.desktop.storage;

import dev.ikm.komet.kview.storage.StorageGestures;
import dev.ikm.komet.layout.storage.KlStorage;
import dev.ikm.komet.layout.storage.KlStorageOffer;
import dev.ikm.komet.layout.storage.KlStoredGroup;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.entity.boundary.DataKind;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Everything in the knowledge base that can be removed as a whole, in one list: documents and
 * conversations, by who else gets them, each with where it came from, its size, and what can be done
 * with it (IKE-Network/ike-issues#1313; step 3 of {@code plan-2026-11-22-remove-export-import-data}).
 * Groups that no other list in Komet shows come last. The sizes are counted in the background, so
 * the list is there at once.
 */
public final class StorageView {

    private static final Logger LOG = LoggerFactory.getLogger(StorageView.class);

    /** The sections of the list, in order. */
    private enum Section {
        SHARED("Shared with everyone who syncs this knowledge base"),
        FILE("Saved in files"),
        LOCAL("Only in this knowledge base"),
        NOT_SHOWN("Not shown anywhere else");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        static Section of(KlStoredGroup group) {
            if (!group.shown()) {
                return NOT_SHOWN;
            }
            return switch (group.kind()) {
                case SHARED -> SHARED;
                case FILE -> FILE;
                case LOCAL, KNOWLEDGE -> LOCAL;
            };
        }
    }

    private final Supplier<EditCoordinate> edit;
    private final VBox root = new VBox();
    private final VBox rows = new VBox();
    private final Label countLabel = new Label("0");
    private final Label status = new Label();
    private final Button again = new Button("Read Again");
    /** The reading under way; one that ends after another began is dropped. */
    private final AtomicInteger reading = new AtomicInteger();
    private final Map<KlStoredGroup, Label> sizes = new HashMap<>();

    /**
     * Builds the list, empty until {@link #read} is called.
     *
     * @param edit the edit coordinate of the person, read as a gesture is made
     */
    public StorageView(Supplier<EditCoordinate> edit) {
        this.edit = edit;
        root.getStyleClass().add("unpublished-changes-container");
        root.setId("storage");
        Label title = new Label("Storage");
        title.getStyleClass().add("title-label");
        countLabel.getStyleClass().add("count-pill");
        HBox titleRow = new HBox(title, countLabel);
        titleRow.getStyleClass().add("title-row");
        Label subtitle = new Label("The documents and conversations in this knowledge base, and what can be done with each");
        subtitle.getStyleClass().add("subtitle-label");
        subtitle.setWrapText(true);
        VBox header = new VBox(titleRow, subtitle);
        header.getStyleClass().add("header");

        rows.setId("storageRows");
        rows.setPadding(new Insets(0, 12, 12, 12));
        rows.setSpacing(2);
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: white; -fx-background-color: white; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        status.setId("storageStatus");
        status.setWrapText(true);
        again.setId("storageReadAgain");
        again.getStyleClass().add("discard-all-button");
        again.setTooltip(new Tooltip("Read what the knowledge base has stored again"));
        again.setOnAction(e -> read());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox buttons = new HBox(8, status, spacer, again);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().add("footer-buttons");
        VBox footer = new VBox(buttons);
        footer.getStyleClass().add("footer");
        root.getChildren().addAll(header, scroll, footer);
    }

    /** @return the list's node */
    public VBox fxObject() {
        return root;
    }

    /** Reads what the knowledge base has stored, then counts each group. Call on the JavaFX thread. */
    public void read() {
        int mine = reading.incrementAndGet();
        if (!PrimitiveData.running()) {
            draw(List.of());
            status.setText("No knowledge base is open.");
            return;
        }
        status.setText("Reading what the knowledge base has stored …");
        CompletableFuture.supplyAsync(KlStorage::groups).thenCompose(groups -> {
            Platform.runLater(() -> {
                if (mine == reading.get()) {
                    draw(groups);
                    status.setText("Counting …");
                }
            });
            return CompletableFuture.supplyAsync(() -> KlStorage.measure(groups));
        }).whenComplete((measured, failure) -> Platform.runLater(() -> {
            if (mine != reading.get()) {
                return;
            }
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                LOG.warn("Could not read what the knowledge base has stored", cause);
                status.setText("Could not read what the knowledge base has stored: " + cause.getMessage());
                return;
            }
            draw(measured.stream().map(KlStorage.Sized::group).toList());
            long components = 0;
            for (KlStorage.Sized sized : measured) {
                components += sized.components();
                Label size = sizes.get(sized.group());
                if (size != null) {
                    size.setText(components(sized.components()));
                }
            }
            status.setText(measured.isEmpty() ? "" : components(components) + " in all");
        }));
    }

    private static String components(long count) {
        return count == 1 ? "1 component" : String.format("%,d components", count);
    }

    private void draw(List<KlStoredGroup> groups) {
        sizes.clear();
        countLabel.setText(Integer.toString(groups.size()));
        Map<Section, List<KlStoredGroup>> bySection = new HashMap<>();
        for (KlStoredGroup group : groups) {
            bySection.computeIfAbsent(Section.of(group), _ -> new ArrayList<>()).add(group);
        }
        List<Region> drawn = new ArrayList<>();
        for (Section section : Section.values()) {
            List<KlStoredGroup> here = bySection.getOrDefault(section, List.of());
            if (here.isEmpty()) {
                continue;
            }
            Label heading = new Label(section.title + " (" + here.size() + ")");
            heading.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            heading.setPadding(new Insets(12, 0, 4, 0));
            heading.setWrapText(true);
            drawn.add(heading);
            here.forEach(group -> drawn.add(row(group)));
        }
        if (drawn.isEmpty()) {
            Label none = new Label("Nothing is stored here that can be removed as a whole.");
            none.setPadding(new Insets(12, 0, 0, 0));
            none.setWrapText(true);
            drawn.add(none);
        }
        rows.getChildren().setAll(drawn);
    }

    private HBox row(KlStoredGroup group) {
        TextField name = selectable(group.name(), "-fx-font-weight: bold;");
        TextField from = selectable(group.from(), "-fx-text-fill: #5f6368;");
        VBox words = new VBox(name, from);
        words.setMinWidth(160);
        HBox.setHgrow(words, Priority.ALWAYS);
        Label size = new Label("");
        size.setMinWidth(Region.USE_PREF_SIZE);
        size.setStyle("-fx-text-fill: #5f6368;");
        sizes.put(group, size);
        HBox row = new HBox(8, words, size);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 0, 4, 0));
        for (KlStorageOffer offer : offers(group)) {
            Button button = new Button(offer.label());
            button.setId(offer.id());
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setTooltip(new Tooltip(offer.tooltip()));
            button.setOnAction(e -> {
                button.setDisable(true);
                offer.perform().perform(button, edit.get(), this::read);
            });
            row.getChildren().add(button);
        }
        return row;
    }

    /** What can be done with a group: what its provider offers, or, when none knows it, deleting it. */
    private static List<KlStorageOffer> offers(KlStoredGroup group) {
        if (group.provider() != null || group.kind() == DataKind.SHARED) {
            return group.offers();
        }
        KlStorageOffer delete = new KlStorageOffer("storageDelete", "Delete…", "Delete this from the knowledge base",
                (node, edit, done) -> StorageGestures.delete(node, group, done));
        return group.kind() == DataKind.LOCAL
                ? List.of(new KlStorageOffer("storageExport", "Export…", "Write this to a file that another knowledge base can import",
                        (node, edit, done) -> StorageGestures.export(node, group.root(), group.name(), edit, done)), delete)
                : List.of(delete);
    }

    /** Text a person can select and copy, as a label would show it. */
    private static TextField selectable(String words, String style) {
        TextField field = new TextField(words);
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; -fx-border-width: 0;"
                + " -fx-effect: null; -fx-padding: 0; " + style);
        if (!words.isBlank()) {
            field.setTooltip(new Tooltip(words));
        }
        return field;
    }
}
