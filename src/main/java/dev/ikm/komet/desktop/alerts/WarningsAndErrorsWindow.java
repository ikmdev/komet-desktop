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

import dev.ikm.tinkar.common.alert.AlertLog;
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.application.Platform;
import javafx.stage.Stage;

import static dev.ikm.komet.desktop.util.CssFile.KOMET_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KVIEW_CSS;
import static dev.ikm.komet.desktop.util.CssUtils.addStylesheets;

/**
 * The Warnings and Errors list in a window of its own, which the View menu and the landing
 * page open. There is one; asking for it again brings it to the front.
 */
public final class WarningsAndErrorsWindow {

    private static Stage stage;
    private static WarningsAndErrorsView view;
    private static Runnable landingPageFollower;

    private WarningsAndErrorsWindow() {
    }

    /** Shows the window, or brings it to the front. Call on the JavaFX thread. */
    public static void show() {
        if (stage == null) {
            view = new WarningsAndErrorsView(AlertLog.root());
            stage = new Stage();
            stage.setTitle("Warnings and Errors");
            Scene scene = new Scene(view.fxObject(), 720, 560);
            addStylesheets(scene, KOMET_CSS, KVIEW_CSS);
            stage.setScene(scene);
            view.seenWhile(() -> stage.isShowing() && stage.isFocused());
            stage.focusedProperty().addListener((obs, was, is) -> {
                if (is) {
                    view.shown();
                }
            });
        }
        stage.show();
        stage.toFront();
        view.shown();
    }

    /**
     * Has the landing page's link say how many warnings and errors there are, and open the
     * window when selected.
     *
     * @param link the landing page's Warnings and Errors link
     */
    public static void follow(Hyperlink link) {
        AlertLog log = AlertLog.root();
        if (landingPageFollower != null) {
            log.removeListener(landingPageFollower);
        }
        Runnable update = () -> link.setText(WarningsAndErrors.summary(log.entries(), log.unseenCount()));
        landingPageFollower = () -> Platform.runLater(update);
        log.addListener(landingPageFollower);
        link.setOnAction(e -> {
            show();
            link.setVisited(false);
        });
        update.run();
    }
}
