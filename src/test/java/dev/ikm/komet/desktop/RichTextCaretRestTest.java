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

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Subscription;
import jfx.incubator.scene.control.richtext.RichTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RichTextCaretRest} in a headless window: JavaFX stops pulsing once no rich text area has
 * the focus, the focused one still blinks, and an area gone from its window can be collected.
 * Without it, one area anywhere keeps JavaFX pulsing at the display rate and is never collected.
 */
class RichTextCaretRestTest {

    /**
     * Pulses counted over {@link #MEASURE_MILLIS}: a running caret gives about 30 on an idle machine,
     * rest about 0. A caret runs when the count is above rest; a loaded machine pulses less often
     * (13 on a parallel CI build), so no higher floor is asked of it.
     */
    private static final int AT_REST_AT_MOST = 3;
    private static final long SETTLE_MILLIS = 400;
    private static final long MEASURE_MILLIS = 600;

    private final AtomicInteger pulses = new AtomicInteger();
    private Subscription rest;
    private Stage stage;
    private VBox root;
    private Button elsewhere;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the JavaFX toolkit did not start");
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void showWindow() {
        onFx(() -> {
            rest = RichTextCaretRest.install();
            elsewhere = new Button("elsewhere");
            root = new VBox(elsewhere);
            Scene scene = new Scene(root, 400, 500);
            scene.addPostLayoutPulseListener(pulses::incrementAndGet);
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
            stage.requestFocus();
            return null;
        });
    }

    @AfterEach
    void closeWindow() {
        onFx(() -> {
            stage.close();
            rest.unsubscribe();
            return null;
        });
    }

    @Test
    void anAreaWithoutTheFocusLeavesJavaFxAtRest() {
        onFx(() -> {
            root.getChildren().addAll(area(true), area(false));
            elsewhere.requestFocus();
            return null;
        });
        assertAtRest("two rich text areas, neither focused");
    }

    @Test
    void theFocusedAreaBlinksAndRestsWhenTheFocusLeaves() {
        RichTextArea area = onFx(() -> {
            RichTextArea made = area(true);
            root.getChildren().add(made);
            return made;
        });
        onFx(() -> { area.requestFocus(); return null; });
        assertBlinks(area, "the focused area");

        onFx(() -> { elsewhere.requestFocus(); return null; });
        assertAtRest("the focus moved to a button");

        onFx(() -> { area.requestFocus(); return null; });
        assertBlinks(area, "the area focused again");
    }

    @Test
    void anAreaRemovedWithTheFocusRestsAndIsCollected() {
        WeakReference<RichTextArea> removed = onFx(() -> {
            RichTextArea area = area(true);
            root.getChildren().add(area);
            area.requestFocus();
            return new WeakReference<>(area);
        });
        settle();
        onFx(() -> { root.getChildren().remove(removed.get()); return null; });
        assertAtRest("the focused area removed");
        assertCollected(removed, "the area removed with the focus");
    }

    @Test
    void anAreaInAWindowClosedWithTheFocusIsCollected() {
        WeakReference<RichTextArea> closed = onFx(() -> {
            RichTextArea area = area(true);
            root.getChildren().add(area);
            area.requestFocus();
            return new WeakReference<>(area);
        });
        settle();
        onFx(() -> {
            stage.close();
            // the test holds the window; only the area's own references count
            stage.setScene(null);
            root = null;
            elsewhere = null;
            return null;
        });
        assertCollected(closed, "the area whose window closed while it had the focus");
    }

    @Test
    void theStylesheetGoesOnEveryWindowOnce() {
        Scene scene = onFx(() -> stage.getScene());
        assertEquals(1, onFx(() -> scene.getStylesheets().stream()
                .filter(RichTextCaretRest.STYLESHEET_URL::equals).count()));
    }

    private static RichTextArea area(boolean editable) {
        RichTextArea area = new RichTextArea();
        area.setEditable(editable);
        area.appendText("Some text\n");
        area.setPrefHeight(120);
        return area;
    }

    private void assertAtRest(String what) {
        int counted = pulsesAfterSettling();
        assertTrue(counted <= AT_REST_AT_MOST,
                what + ": JavaFX should be at rest, but " + counted + " pulses ran in " + MEASURE_MILLIS + " ms");
    }

    private void assertBlinks(RichTextArea area, String what) {
        settle();
        AtomicInteger flips = new AtomicInteger();
        Subscription watching = onFx(() -> {
            assertTrue(area.isFocused(), what + " should have the focus");
            Subscription all = Subscription.EMPTY;
            for (Node caret : area.lookupAll(".caret")) {
                all = all.and(caret.visibleProperty().subscribe((was, is) -> flips.incrementAndGet()));
            }
            return all;
        });
        int counted = measure();
        onFx(() -> { watching.unsubscribe(); return null; });
        assertTrue(counted > AT_REST_AT_MOST, what + ": the caret should run, but only " + counted + " pulses ran in "
                + MEASURE_MILLIS + " ms, no more than at rest");
        assertTrue(flips.get() >= 1, what + ": the caret never blinked");
    }

    private static void assertCollected(WeakReference<?> reference, String what) {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            sleep(100);
        }
        assertNull(reference.get(), what + " is still held after it left its window");
    }

    private int pulsesAfterSettling() {
        settle();
        return measure();
    }

    private int measure() {
        pulses.set(0);
        sleep(MEASURE_MILLIS);
        return pulses.get();
    }

    private static void settle() {
        sleep(SETTLE_MILLIS);
    }

    private static <T> T onFx(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
