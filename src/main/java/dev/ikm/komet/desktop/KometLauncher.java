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

import javafx.application.Application;

/**
 * Process entry point for Komet Desktop.
 *
 * <p>This class deliberately does <em>not</em> extend {@link Application}. When the main
 * class is an {@code Application} subclass and {@code javafx.graphics} is a boot-layer
 * module (jlink image, jpackage {@code .app}, {@code java -m}), the JDK launcher hands
 * the class to JavaFX's own launcher, which starts the toolkit <em>before</em> it invokes
 * {@code main(String[])}. Prism chooses its rendering pipeline during toolkit start, so a
 * {@code prism.order} set inside {@code App.main()} arrived too late and was silently
 * ignored in every packaged build (ikmdev/komet-desktop#183, second round). A plain class
 * gets its {@code main} run first, so the system properties below are in place when
 * {@link Application#launch(Class, String...)} starts the toolkit.
 *
 * <p>All launchers (jpackage, the jlink images, the IDE run configurations) must name this
 * class as the main class, not {@link App}.
 */
public final class KometLauncher {

    private KometLauncher() {
    }

    public static void main(String[] args) {
        configureMacOSRenderingPipeline();
        Application.launch(App.class, args);
    }

    /**
     * Renders through the OpenGL (es2) Prism pipeline on macOS instead of Metal, the JavaFX 27
     * default there. With Metal (JavaFX 27-ea+24) the application crashes natively in
     * {@code MTLContext.nUpdateRenderTarget} — an Apple crash report, no Java exception — once
     * enough KL window content is on screen at the same time (a handful of open KL concept
     * windows, or a KL pattern window with several fields); with es2 it does not
     * (ikmdev/komet-desktop#183). A {@code -Dprism.order} given on the command line wins, so
     * Metal can still be chosen explicitly — e.g. to retest it against a newer JavaFX.
     */
    private static void configureMacOSRenderingPipeline() {
        if (App.IS_MAC && System.getProperty("prism.order") == null) {
            System.setProperty("prism.order", "es2,sw");
        }
    }
}
