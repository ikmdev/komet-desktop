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

import dev.ikm.komet.layout.tray.KlSidebarTray;
import dev.ikm.komet.layout.tray.KlSidebarTrayProvider;
import dev.ikm.tinkar.common.alert.AlertLog;
import javafx.beans.value.ObservableIntegerValue;
import javafx.scene.layout.Pane;

import java.util.UUID;

/**
 * Puts the Warnings and Errors list on the journal's left sidebar, with the count of what was
 * reported since a person last looked riding its icon.
 */
public final class WarningsAndErrorsTrayProvider implements KlSidebarTrayProvider {

    /** The id of the journal sidebar's Warnings and Errors toggle. */
    public static final String TOGGLE_ID = "warningsAndErrorsToggleButton";

    /** Required by the service loader. */
    public WarningsAndErrorsTrayProvider() {
    }

    @Override
    public String toggleId() {
        return TOGGLE_ID;
    }

    @Override
    public String tooltip() {
        return "Warnings and Errors";
    }

    @Override
    public String iconShape() {
        // A triangle in outline with an exclamation mark.
        return "M12 5.99 19.53 19H4.47L12 5.99M12 2 1 21h22L12 2Zm1 14h-2v2h2v-2Zm0-6h-2v4h2v-4Z";
    }

    @Override
    public KlSidebarTray create(UUID journalTopic) {
        WarningsAndErrorsView view = new WarningsAndErrorsView(AlertLog.root());
        return new KlSidebarTray() {
            @Override
            public Pane fxObject() {
                return view.fxObject();
            }

            @Override
            public ObservableIntegerValue badge() {
                return view.unseenProperty();
            }

            @Override
            public void shown() {
                view.shown();
            }

            @Override
            public void shutdown() {
                view.shutdown();
            }
        };
    }
}
