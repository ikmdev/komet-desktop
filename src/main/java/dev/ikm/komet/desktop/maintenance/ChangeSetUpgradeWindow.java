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

import dev.ikm.komet.desktop.ChangeSetUpgradeCommand;
import dev.ikm.komet.desktop.maintenance.markdown.MarkdownTextModel;
import dev.ikm.tinkar.entity.changeset.ChangeSetCompaction;
import dev.ikm.tinkar.entity.changeset.ChangeSetUpgrade;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Tools → Upgrade Change Sets… (IKE-Network/ike-issues#1313): the open knowledge base is the base.
 * The window starts on the store's own change-set folder, or the folder or files chosen instead,
 * converts every change set that needs it into a work folder, and shows the report. Nothing chosen
 * changes until Replace Originals… is confirmed: then the originals go into a dated {@code .tar.gz}
 * in {@code ~/ike-archive} and their upgrades take their places. Closing the window first discards
 * the upgrades.
 */
public final class ChangeSetUpgradeWindow {
    private static final Logger LOG = LoggerFactory.getLogger(ChangeSetUpgradeWindow.class);
    private static final String STYLESHEET = "/dev/ikm/komet/desktop/maintenance/markdown.css";

    private final Stage stage = new Stage();
    private final Path storeRoot;
    private final BorderPane root = new BorderPane();
    private final Label chosenLabel = new Label();
    private final Label status = new Label("");
    private final Button folderButton = new Button("Choose Folder…");
    private final Button filesButton = new Button("Choose Files…");
    private final Button replaceButton = new Button("Replace Originals…");
    private ChangeSetUpgrade.Plan plan;

    private ChangeSetUpgradeWindow(Path storeRoot, Window owner) {
        this.storeRoot = storeRoot;
        stage.setTitle("Upgrade Change Sets — " + storeRoot.getFileName());
        if (owner != null) {
            stage.initOwner(owner);
        }
        chosenLabel.setWrapText(true);
        status.setWrapText(true);
        folderButton.setOnAction(_ -> chooseFolder());
        filesButton.setOnAction(_ -> chooseFiles());
        replaceButton.setDisable(true);
        replaceButton.setOnAction(_ -> confirmReplacement());
        Button closeButton = new Button("Close");
        closeButton.setOnAction(_ -> stage.close());
        stage.setOnHidden(_ -> discard());

        Label base = new Label("Knowledge base: " + storeRoot);
        HBox top = new HBox(8, base, spacer(), folderButton, filesButton);
        top.setPadding(new Insets(8));
        HBox chosen = new HBox(8, chosenLabel);
        chosen.setPadding(new Insets(0, 8, 8, 8));
        root.setTop(new javafx.scene.layout.VBox(top, chosen));
        HBox toolbar = new HBox(8, status, spacer(), replaceButton, closeButton);
        toolbar.setPadding(new Insets(8));
        root.setBottom(toolbar);

        Scene scene = new Scene(root, 960, 640);
        URL css = ChangeSetUpgradeWindow.class.getResource(STYLESHEET);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
    }

    /**
     * Opens the window on the running store's change-set folder and checks it.
     *
     * @param storeRoot the open knowledge base's folder
     * @param owner     the window the dialogs belong to
     */
    public static void open(Path storeRoot, Window owner) {
        ChangeSetUpgradeWindow window = new ChangeSetUpgradeWindow(storeRoot, owner);
        window.stage.show();
        Path folder = ChangeSetUpgradeCommand.changeSetFolder(storeRoot);
        if (Files.isDirectory(folder)) {
            window.check(List.of(folder), "the store's change-set folder, " + folder);
        } else {
            window.chosenLabel.setText("The store has no change-set folder at " + folder + "; choose a folder or files.");
        }
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder of Change Sets to Upgrade");
        Path folder = ChangeSetUpgradeCommand.changeSetFolder(storeRoot);
        if (Files.isDirectory(folder)) {
            chooser.setInitialDirectory(folder.toFile());
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            check(List.of(chosen.toPath()), chosen.toString());
        }
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Change Sets to Upgrade");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Change Set ZIP", "*.zip"));
        Path folder = ChangeSetUpgradeCommand.changeSetFolder(storeRoot);
        if (Files.isDirectory(folder)) {
            chooser.setInitialDirectory(folder.toFile());
        }
        List<File> chosen = chooser.showOpenMultipleDialog(stage);
        if (chosen != null && !chosen.isEmpty()) {
            check(chosen.stream().map(File::toPath).toList(), chosen.size() + " file(s) in " + chosen.getFirst().getParent());
        }
    }

    /** Converts what was chosen into a new work folder and shows the report. */
    private void check(List<Path> chosen, String description) {
        discard();
        chosenLabel.setText("Change sets: " + description);
        Task<ChangeSetUpgrade.Plan> task = new Task<>() {
            @Override
            protected ChangeSetUpgrade.Plan call() throws Exception {
                updateMessage("Assessing the files…");
                List<Path> files = ChangeSetUpgrade.changeSets(chosen);
                Path work = Files.createTempDirectory("komet-change-set-upgrade-");
                return ChangeSetUpgrade.plan(files, ChangeSetCompaction.patternsFromStore(), storeRoot.toString(), work,
                        (index, count, changeSet) -> updateMessage("Upgrading " + index + " of " + count + ": "
                                + changeSet.getFileName()));
            }
        };
        run(task, "change-set-upgrade", () -> {
            plan = task.getValue();
            show(plan.markdown());
            replaceButton.setDisable(plan.replaceable().isEmpty());
            status.setText(plan.replaceable().isEmpty()
                    ? "Nothing to replace."
                    : plan.replaceable().size() + " upgrade(s) ready to replace their originals.");
        });
    }

    private void confirmReplacement() {
        if (plan == null || plan.replaceable().isEmpty()) {
            return;
        }
        Path archives = ChangeSetUpgradeCommand.archiveFolder();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "The " + plan.replaceable().size() + " original(s) go first into a dated archive in " + archives
                        + ", with a SHA-256 manifest and a README; then each is replaced by its upgrade.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Replace Originals");
        confirm.setHeaderText("Replace " + plan.replaceable().size() + " change set(s) with their format-3 upgrades?");
        confirm.initOwner(stage);
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        ChangeSetUpgrade.Plan replacing = plan;
        Task<ChangeSetUpgrade.Replacement> task = new Task<>() {
            @Override
            protected ChangeSetUpgrade.Replacement call() throws Exception {
                updateMessage("Archiving the originals…");
                ChangeSetUpgrade.Archive archive = replacing.archive(archives, LocalDate.now());
                updateMessage("Replacing the originals…");
                return replacing.replace(archive);
            }
        };
        run(task, "change-set-upgrade-replace", () -> {
            ChangeSetUpgrade.Replacement replacement = task.getValue();
            plan = null;
            show(replacing.markdown() + "\n" + replacement.markdown());
            replaceButton.setDisable(true);
            status.setText(replacement.replaced().size() + " replaced; the originals are in "
                    + replacement.archive().getFileName() + ".");
        });
    }

    private void show(String markdown) {
        RichTextArea view = new RichTextArea(MarkdownTextModel.of(markdown));
        view.setEditable(false);
        view.setWrapText(true);
        view.getStyleClass().add("markdown-view");
        root.setCenter(view);
    }

    /** Runs a task off the FX thread with the buttons disabled and its message as the status. */
    private <T> void run(Task<T> task, String threadName, Runnable succeeded) {
        folderButton.setDisable(true);
        filesButton.setDisable(true);
        replaceButton.setDisable(true);
        StackPane busy = new StackPane(new ProgressIndicator());
        busy.setPadding(new Insets(24));
        root.setCenter(busy);
        status.textProperty().bind(task.messageProperty());
        Runnable done = () -> {
            status.textProperty().unbind();
            folderButton.setDisable(false);
            filesButton.setDisable(false);
        };
        task.setOnSucceeded(_ -> {
            done.run();
            succeeded.run();
        });
        task.setOnFailed(_ -> {
            done.run();
            Throwable t = task.getException();
            LOG.error("Change-set upgrade failed against {}", storeRoot, t);
            root.setCenter(null);
            status.setText("");
            Alert error = new Alert(Alert.AlertType.ERROR, "The upgrade failed: "
                    + (t == null ? "unknown error" : t.getMessage()));
            error.setHeaderText(null);
            error.setTitle("Upgrade failed");
            error.initOwner(stage);
            error.showAndWait();
        });
        Thread runner = new Thread(task, threadName);
        runner.setDaemon(true);
        runner.start();
    }

    /** Removes the upgrades of a plan that will not be replaced. */
    private void discard() {
        if (plan == null) {
            return;
        }
        ChangeSetUpgrade.Plan discarded = plan;
        plan = null;
        Thread.ofVirtual().start(() -> {
            try {
                discarded.discard();
            } catch (IOException e) {
                LOG.warn("Could not remove the upgrade work folder {}", discarded.workFolder(), e);
            }
        });
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
