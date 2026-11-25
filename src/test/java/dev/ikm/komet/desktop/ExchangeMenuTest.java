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
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu bars of Komet's windows, in a headless toolkit: each has the Exchange menu, since on macOS the window in
 * front supplies the menu bar.
 */
class ExchangeMenuTest {

    private static final List<String> EXCHANGE_ITEMS = List.of("Info", "Pull", "Sync");

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

    @Test
    void theMenuBarOfAJournalOrClassicWindowHasTheExchangeMenu() {
        Window window = onFx(Window::new);
        onFx(() -> {
            new AppMenu(null).generateMsWindowsMenu(window.root, window.stage);
            return null;
        });
        assertEquals(List.of("File", "Edit", "Exchange", "Window", "Help"), menuNames(window));
        assertEquals(EXCHANGE_ITEMS, exchangeItems(window));
    }

    @Test
    void theMenuBarOfTheLandingPageHasTheExchangeMenu() {
        Window window = onFx(Window::new);
        onFx(() -> {
            new AppMenu(null).setupMenus(window.root, window.stage);
            return null;
        });
        assertTrue(menuNames(window).contains("Exchange"), menuNames(window).toString());
        assertEquals(EXCHANGE_ITEMS, exchangeItems(window));
    }

    /** The menus once the toolkit has run what adding them left for later. */
    private static List<String> menuNames(Window window) {
        onFx(() -> null);
        return onFx(() -> menuBar(window).getMenus().stream().map(Menu::getText).toList());
    }

    private static List<String> exchangeItems(Window window) {
        return onFx(() -> menuBar(window).getMenus().stream().filter(menu -> "Exchange".equals(menu.getText()))
                .flatMap(menu -> menu.getItems().stream()).map(MenuItem::getText).toList());
    }

    private static MenuBar menuBar(Window window) {
        return (MenuBar) window.root.getTop();
    }

    private static final class Window {
        private final BorderPane root = new BorderPane();
        private final Stage stage = new Stage();

        private Window() {
            stage.setScene(new Scene(root, 400, 300));
            stage.setTitle("Window");
        }
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
}
