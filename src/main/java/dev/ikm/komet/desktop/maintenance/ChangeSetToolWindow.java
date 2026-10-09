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

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * A window for one of the change set tools: inspect, verify, expand, compact. The tool runs
 * off the FX thread while a progress indicator shows; its report, plain text one fact or
 * finding per line, replaces the indicator when it is done. A failure closes the window and
 * says why.
 */
public final class ChangeSetToolWindow {
    private static final Logger LOG = LoggerFactory.getLogger(ChangeSetToolWindow.class);

    private ChangeSetToolWindow() {
    }

    /**
     * Opens the window and runs the tool.
     *
     * @param title     what the tool does, for the window's title
     * @param changeSet the change set the tool reads
     * @param tool      produces the report
     * @param owner     the window the dialogs belong to
     */
    public static void open(String title, File changeSet, Callable<String> tool, Window owner) {
        Stage stage = new Stage();
        stage.setTitle(title + " — " + changeSet.getName());

        ProgressIndicator progress = new ProgressIndicator();
        Label progressLabel = new Label(title + " of " + changeSet.getName() + "…");
        StackPane center = new StackPane(progress);
        center.setPadding(new Insets(24));
        Label status = new Label("");
        status.setWrapText(true);
        Button closeButton = new Button("Close");
        closeButton.setOnAction(_ -> stage.close());
        HBox toolbar = new HBox(8, status, spacer(), closeButton);
        toolbar.setPadding(new Insets(8));

        BorderPane root = new BorderPane();
        HBox progressTop = new HBox(8, progressLabel);
        progressTop.setPadding(new Insets(8));
        root.setTop(progressTop);
        root.setCenter(center);
        root.setBottom(toolbar);
        stage.setScene(new Scene(root, 960, 640));
        stage.show();

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return tool.call();
            }
        };
        task.setOnSucceeded(_ -> Platform.runLater(() -> {
            String report = task.getValue();
            TextArea view = new TextArea(report);
            view.setEditable(false);
            view.setWrapText(false);
            view.setStyle("-fx-font-family: monospace;");
            root.setTop(null);
            root.setCenter(view);
            status.setText(String.format("%,d line(s).", report == null ? 0 : report.lines().count()));
        }));
        task.setOnFailed(_ -> Platform.runLater(() -> {
            Throwable t = task.getException();
            LOG.error("{} failed for {}", title, changeSet.getAbsolutePath(), t);
            stage.close();
            Alert error = new Alert(Alert.AlertType.ERROR,
                    title + " of " + changeSet.getName() + " failed: " + (t == null ? "unknown error" : t.getMessage()));
            error.setHeaderText(null);
            error.setTitle(title + " failed");
            if (owner != null) {
                error.initOwner(owner);
            }
            error.showAndWait();
        }));
        Thread runner = new Thread(task, "change-set-tool");
        runner.setDaemon(true);
        runner.start();
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
