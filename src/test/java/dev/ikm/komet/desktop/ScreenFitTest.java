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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reopened windows land on a connected screen (IKE-Network/ike-issues#1151).
 */
class ScreenFitTest {

    /** A laptop screen below the menu bar, and an external monitor to its right. */
    private static final Rectangle2D LAPTOP = new Rectangle2D(0, 25, 1512, 957);
    private static final Rectangle2D EXTERNAL = new Rectangle2D(1512, 0, 2560, 1415);

    @Test
    void aWindowOnAConnectedScreenKeepsItsBounds() {
        Rectangle2D window = new Rectangle2D(1700, 100, 1200, 900);

        assertEquals(window, ScreenFit.fit(window, List.of(LAPTOP, EXTERNAL)));
    }

    @Test
    void aWindowOverlappingTwoScreensKeepsItsBounds() {
        Rectangle2D window = new Rectangle2D(1300, 200, 800, 600);

        assertEquals(window, ScreenFit.fit(window, List.of(LAPTOP, EXTERNAL)));
    }

    @Test
    void aWindowOnADisconnectedMonitorMovesToThePrimary() {
        Rectangle2D window = new Rectangle2D(1700, 100, 1200, 900);

        Rectangle2D fitted = ScreenFit.fit(window, List.of(LAPTOP));

        assertEquals(new Rectangle2D(156, 53.5, 1200, 900), fitted);
    }

    @Test
    void aWindowLargerThanThePrimaryIsShrunkToFit() {
        Rectangle2D window = new Rectangle2D(3000, 0, 2400, 1400);

        assertEquals(LAPTOP, ScreenFit.fit(window, List.of(LAPTOP)));
    }

    @Test
    void aWindowWhoseTitleBarIsOffScreenMoves() {
        // Mostly on the laptop, but the title bar is above the screen and cannot be grabbed.
        Rectangle2D window = new Rectangle2D(100, -500, 800, 900);

        Rectangle2D fitted = ScreenFit.fit(window, List.of(LAPTOP));

        assertEquals(new Rectangle2D(356, 53.5, 800, 900), fitted);
    }

    @Test
    void aSlimSliverOnScreenIsNotEnoughToGrab() {
        // Only 40 px of the title strip reaches the laptop's right edge.
        Rectangle2D window = new Rectangle2D(1472, 100, 800, 600);

        Rectangle2D fitted = ScreenFit.fit(window, List.of(LAPTOP));

        assertEquals(new Rectangle2D(356, 203.5, 800, 600), fitted);
    }

    @Test
    void withNoScreensTheBoundsAreKept() {
        Rectangle2D window = new Rectangle2D(-5000, -5000, 800, 600);

        assertEquals(window, ScreenFit.fit(window, List.of()));
    }
}
