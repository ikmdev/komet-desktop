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

import dev.ikm.komet.desktop.App;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.terms.ConceptFacade;
import javafx.scene.Scene;
import javafx.stage.Stage;

import static dev.ikm.komet.desktop.util.CssFile.KOMET_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KVIEW_CSS;
import static dev.ikm.komet.desktop.util.CssUtils.addStylesheets;

/**
 * The Storage list in a window of its own, which the View menu opens. There is one; asking for it
 * again brings it to the front and reads the list again.
 */
public final class StorageWindow {

    private static Stage stage;
    private static StorageView view;

    private StorageWindow() {
    }

    /** Shows the window, or brings it to the front. Call on the JavaFX thread. */
    public static void show() {
        if (stage == null) {
            view = new StorageView(StorageWindow::person);
            stage = new Stage();
            stage.setTitle("Storage");
            Scene scene = new Scene(view.fxObject(), 860, 620);
            addStylesheets(scene, KOMET_CSS, KVIEW_CSS);
            stage.setScene(scene);
        }
        stage.show();
        stage.toFront();
        view.read();
    }

    /** The default edit coordinate, as the person signed in. */
    private static EditCoordinate person() {
        EditCoordinateRecord standard = Coordinates.Edit.Default();
        ConceptFacade person = App.userProperty.get();
        return person == null ? standard : EditCoordinateRecord.make(person.nid(), standard.getDefaultModuleNid(),
                standard.getDestinationModuleNid(), standard.getDefaultPathNid(), standard.getPromotionPathNid());
    }
}
