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

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.Subscription;
import jfx.incubator.scene.control.richtext.RichTextArea;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Lets a {@link RichTextArea} blink its caret only while it has the focus.
 * <p>The incubator skin starts the caret's blink {@code Timeline} when it is made and never stops
 * it: not when the area loses the focus, is read-only, leaves its scene or its window closes, and
 * not when the skin is disposed. A running animation makes JavaFX lay out and draw every window
 * about 60 times a second, so one area anywhere kept an idle Komet pulsing; and the animation
 * timer holds the running {@code Timeline}, so every area ever shown stayed in memory with all it
 * holds. (JavaFX 27; the same in openjdk/jfx master on 2026-11-07.)
 * <p>The skin restarts the {@code Timeline} whenever {@link RichTextArea#caretBlinkPeriodProperty()}
 * changes, and a {@code Timeline} whose cycle lasts no time does not start. So this class gives
 * every window's scene a stylesheet that sets the period to zero, except on the focused area:
 * <pre>{@code
 * .rich-text-area         { -fx-caret-blink-period: 0ms; }
 * .rich-text-area:focused { -fx-caret-blink-period: 1000ms; }
 * }</pre>
 * That covers an area that loses the focus while it stays in a shown scene. It cannot cover the
 * focused area leaving its scene, or its window closing, because JavaFX applies no styles to a
 * node outside a shown scene; the last value, 1000 ms, would stay and keep the caret running. So
 * this class also watches each scene's focus owner and sets the period to zero itself when the
 * area that had the focus is gone from the scene or its window hides. A stylesheet value wins over
 * one set in code, so an area put back in a scene blinks again as soon as it has the focus.
 * <p>Popup windows take their owner scene's stylesheets, so they need no stylesheet of their own;
 * their focus owners are watched like any other window's.
 */
public final class RichTextCaretRest {

    /** The stylesheet each tracked window's scene carries, as a {@code data:} URL. */
    static final String STYLESHEET_URL = "data:text/css;base64," + Base64.getEncoder().encodeToString("""
            .rich-text-area         { -fx-caret-blink-period: 0ms; }
            .rich-text-area:focused { -fx-caret-blink-period: 1000ms; }
            """.getBytes(StandardCharsets.UTF_8));

    private RichTextCaretRest() {
    }

    /**
     * Starts with the windows already showing and every window shown from now on. Call once, on
     * the FX thread, before the first window is shown.
     *
     * @return a subscription that stops the tracking and takes the stylesheet off every window
     */
    public static Subscription install() {
        Installer installer = new Installer();
        return installer::uninstall;
    }

    /** Lets the caret of {@code area} rest, where no stylesheet will reach it again. */
    private static void rest(Node node) {
        if (node instanceof RichTextArea area) {
            area.setCaretBlinkPeriod(Duration.ZERO);
        }
    }

    private static final class Installer {

        private final Map<Window, Subscription> windows = new HashMap<>();
        private final ListChangeListener<Window> onWindows = change -> {
            while (change.next()) {
                change.getRemoved().forEach(this::forget);
                change.getAddedSubList().forEach(this::track);
            }
        };

        Installer() {
            Window.getWindows().forEach(this::track);
            Window.getWindows().addListener(onWindows);
        }

        private void track(Window window) {
            if (windows.containsKey(window)) {
                return;
            }
            Subscription onScene = window.sceneProperty().subscribe(scene -> {
                if (scene != null && !(window instanceof PopupWindow)
                        && !scene.getStylesheets().contains(STYLESHEET_URL)) {
                    scene.getStylesheets().add(STYLESHEET_URL);
                }
            });
            Subscription onFocusOwner = window.sceneProperty().flatMap(Scene::focusOwnerProperty)
                    .subscribe((was, is) -> {
                        if (was != null && was.getScene() != window.getScene()) {
                            rest(was);
                        }
                    });
            windows.put(window, onScene.and(onFocusOwner));
        }

        /**
         * A window leaves {@link Window#getWindows()} as it hides, before its showing listeners
         * hear of it, and its scene keeps its focus owner; that area is put to rest here.
         */
        private void forget(Window window) {
            Subscription subscription = windows.remove(window);
            if (subscription != null) {
                subscription.unsubscribe();
            }
            if (window.getScene() != null) {
                rest(window.getScene().getFocusOwner());
            }
        }

        private void uninstall() {
            Window.getWindows().removeListener(onWindows);
            for (Map.Entry<Window, Subscription> entry : windows.entrySet()) {
                entry.getValue().unsubscribe();
                Scene scene = entry.getKey().getScene();
                if (scene != null) {
                    scene.getStylesheets().remove(STYLESHEET_URL);
                }
            }
            windows.clear();
        }
    }
}
