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
package dev.ikm.komet.desktop;

import dev.ikm.komet.desktop.OpenWindows.Entry;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import javafx.geometry.Rectangle2D;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tracks the reopenable windows (journals and KL editor windows) in stacking order, and records
 * them and the landing page's place in the knowledge base's preferences, so the next launch
 * reopens them where they were (IKE-Network/ike-issues#1151).
 *
 * <p>JavaFX exposes no stacking order, so the order is the order of focus: a window moves to the
 * front of the list when it gains focus. A window the user closes leaves the list at once; one
 * closed by quitting does not, so the recorded list is the windows open at quit.
 */
final class OpenWindowTracker {

    private static final Logger LOG = LoggerFactory.getLogger(OpenWindowTracker.class);

    /** The preferences node, under the knowledge base's configuration root. */
    static final String NODE = "open-windows";

    /** The keys in {@link #NODE}. */
    enum Key {
        /** The windows open at quit, back to front. */
        OPEN_WINDOWS,
        /** The landing page's bounds at quit. */
        LANDING_PAGE_BOUNDS
    }

    /** Back to front: the last stage was in front. */
    private final List<Stage> order = new ArrayList<>();
    private final Map<Stage, Supplier<Optional<Entry>>> entries = new HashMap<>();

    /**
     * Starts tracking a window. {@code entry} is asked for the window's entry each time the list
     * is recorded, so a KL editor window records the layout it holds then, and none while its
     * layout is unsaved.
     *
     * @param stage the window
     * @param entry what to record for it; empty when it should not be reopened
     */
    void track(Stage stage, Supplier<Optional<Entry>> entry) {
        order.remove(stage);
        order.add(stage);
        entries.put(stage, entry);
        stage.focusedProperty().subscribe(focused -> {
            if (focused && entries.containsKey(stage)) {
                order.remove(stage);
                order.add(stage);
            }
        });
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> {
            if (App.shutdownInProgress) {
                // Closed by quitting: it was open at quit, so it stays recorded.
                return;
            }
            order.remove(stage);
            entries.remove(stage);
            record();
        });
    }

    /**
     * The tracked windows' entries, back to front.
     *
     * @return the entries
     */
    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Stage stage : order) {
            try {
                entries.get(stage).get().ifPresent(result::add);
            } catch (RuntimeException ex) {
                LOG.warn("Could not record an open window; it will not be reopened", ex);
            }
        }
        return result;
    }

    /**
     * Records the tracked windows in the knowledge base's preferences.
     */
    void record() {
        try {
            KometPreferences preferences = preferences();
            preferences.putList(Key.OPEN_WINDOWS, OpenWindows.encodeAll(entries()));
            preferences.flush();
        } catch (Exception ex) {
            LOG.warn("Could not record the open windows", ex);
        }
    }

    /**
     * Records the landing page's bounds in the knowledge base's preferences.
     *
     * @param landingPage the landing page's stage
     */
    static void recordLandingPage(Stage landingPage) {
        try {
            KometPreferences preferences = preferences();
            preferences.put(Key.LANDING_PAGE_BOUNDS, OpenWindows.encodeBounds(new Rectangle2D(
                    landingPage.getX(), landingPage.getY(), landingPage.getWidth(), landingPage.getHeight())));
            preferences.flush();
        } catch (Exception ex) {
            LOG.warn("Could not record the landing page's place", ex);
        }
    }

    /**
     * The windows recorded as open at the last quit, back to front.
     *
     * @return the recorded entries
     */
    static List<Entry> recordedWindows() {
        try {
            return OpenWindows.decodeAll(preferences().getList(Key.OPEN_WINDOWS));
        } catch (Exception ex) {
            LOG.warn("Could not read the windows open at the last quit", ex);
            return List.of();
        }
    }

    /**
     * The landing page's bounds recorded at the last quit.
     *
     * @return the bounds, or empty when none were recorded
     */
    static Optional<Rectangle2D> recordedLandingPage() {
        try {
            return preferences().get(Key.LANDING_PAGE_BOUNDS).flatMap(OpenWindows::decodeBounds);
        } catch (Exception ex) {
            LOG.warn("Could not read the landing page's place", ex);
            return Optional.empty();
        }
    }

    private static KometPreferences preferences() {
        return KometPreferencesImpl.getConfigurationRootPreferences().node(NODE);
    }
}
