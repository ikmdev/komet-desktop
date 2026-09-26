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

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;

/**
 * Keeps a reopened window reachable: a window saved on a monitor that is no longer connected is
 * moved onto one that is (IKE-Network/ike-issues#1151).
 */
final class ScreenFit {

    /** The height of the strip along a window's top edge, where its title bar is. */
    static final double TITLE_STRIP_HEIGHT = 30;

    /** How much of the title strip must be on a screen for the window to count as reachable. */
    static final double MIN_VISIBLE_WIDTH = 100;

    private ScreenFit() {
    }

    /**
     * The bounds to reopen a window at. A window whose title strip is on a screen, far enough to
     * grab and drag, keeps its bounds. Any other window is centered on the first screen, shrunk
     * to fit it if needed.
     *
     * @param window  the saved bounds
     * @param screens the connected screens' visual bounds, the primary first
     * @return the bounds to use
     */
    static Rectangle2D fit(Rectangle2D window, List<Rectangle2D> screens) {
        if (screens.isEmpty()) {
            return window;
        }
        double stripHeight = Math.min(TITLE_STRIP_HEIGHT, window.getHeight());
        double neededWidth = Math.min(MIN_VISIBLE_WIDTH, window.getWidth());
        for (Rectangle2D screen : screens) {
            double overlapWidth = Math.min(window.getMaxX(), screen.getMaxX()) - Math.max(window.getMinX(), screen.getMinX());
            double overlapHeight = Math.min(window.getMinY() + stripHeight, screen.getMaxY())
                    - Math.max(window.getMinY(), screen.getMinY());
            if (overlapWidth >= neededWidth && overlapHeight >= stripHeight) {
                return window;
            }
        }
        Rectangle2D screen = screens.getFirst();
        double width = Math.min(window.getWidth(), screen.getWidth());
        double height = Math.min(window.getHeight(), screen.getHeight());
        return new Rectangle2D(screen.getMinX() + (screen.getWidth() - width) / 2,
                screen.getMinY() + (screen.getHeight() - height) / 2, width, height);
    }

    /**
     * The visual bounds of the connected screens, the primary first.
     *
     * @return the screens' visual bounds
     */
    static List<Rectangle2D> connectedScreens() {
        Screen primary = Screen.getPrimary();
        return Screen.getScreens().stream()
                .sorted((a, b) -> Boolean.compare(!a.equals(primary), !b.equals(primary)))
                .map(Screen::getVisualBounds)
                .toList();
    }

    /**
     * Places a stage at saved bounds, moved onto a connected screen if needed.
     *
     * @param stage  the stage
     * @param bounds the saved bounds
     */
    static void place(Stage stage, Rectangle2D bounds) {
        Rectangle2D fitted = fit(bounds, connectedScreens());
        stage.setX(fitted.getMinX());
        stage.setY(fitted.getMinY());
        stage.setWidth(fitted.getWidth());
        stage.setHeight(fitted.getHeight());
    }
}
