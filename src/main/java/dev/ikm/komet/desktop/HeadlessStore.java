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

import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceExclusionGroup;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;
import dev.ikm.tinkar.common.service.ServiceProperties;
import javafx.application.Platform;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A knowledge base opened the way {@code --kb} opens it in the window, with no window: what the
 * headless commands share (IKE-Network/ike-issues#1313). JavaFX runs on its Headless platform,
 * because Komet's executor reports each task's progress through the toolkit and a store does not
 * stop without one; the person sees nothing of it.
 */
final class HeadlessStore {

    private HeadlessStore() {
    }

    /**
     * Starts logging, the headless toolkit and the plugin system: what a command does once its
     * arguments check and before it opens anything.
     */
    static void startPlatform() {
        App.initLog4J2FromConf();
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("java.awt.headless", "true");
        Platform.setImplicitExit(false);
        Platform.startup(() -> { });
        App.initializePluginSystem();
    }

    /**
     * Opens the knowledge base, remembering nothing.
     *
     * @param knowledgeBase the {@code --kb} value: a store's name, or its folder
     * @param err           where a refusal is explained
     * @param thenWhat      what to do after closing it elsewhere, for the refusal's advice
     * @return whether the store started; a refusal has been explained on {@code err}
     */
    static boolean open(String knowledgeBase, PrintStream err, String thenWhat) {
        ServiceLifecycleManager lifecycleManager = ServiceLifecycleManager.get();
        if (!lifecycleManager.isDiscovered()) {
            lifecycleManager.discoverServices();
        }
        List<DataServiceController<?>> controllers = new ArrayList<>();
        lifecycleManager.getServicesForGroup(ServiceExclusionGroup.DATA_PROVIDER)
                .forEach(service -> controllers.add((DataServiceController<?>) service));
        Path home = Path.of(System.getProperty("user.home"));
        KnowledgeBaseResolver.Resolution resolution = KnowledgeBaseResolver.resolve(knowledgeBase,
                home.resolve("Solor"), home, KnowledgeBaseResolver.candidates(controllers));
        switch (resolution) {
            case KnowledgeBaseResolver.Unresolved unresolved -> {
                err.println("--" + LaunchOptions.Option.KB.argumentName() + "=" + knowledgeBase + ": " + unresolved.message());
                return false;
            }
            case KnowledgeBaseResolver.Found found -> {
                DataServiceController<?> controller = controllers.stream()
                        .filter(candidate -> candidate.controllerName().equals(found.controllerName()))
                        .findFirst().orElseThrow();
                Optional<String> conflict = controller.openConflict(found.option());
                if (conflict.isPresent()) {
                    err.println(found.option().name() + " is open in another process: " + conflict.get()
                            + ". Close it there, then " + thenWhat + ".");
                    return false;
                }
                err.println("Opening " + found.option().uri() + " with " + found.controllerName());
                SelectDataSourceController.selectDataSource(controller, found.option());
                PrimitiveData.start();
                return true;
            }
        }
    }

    /**
     * The open store's root folder.
     *
     * @return the folder
     * @throws IllegalStateException if the store started with no data store root
     */
    static Path storeRoot() {
        Optional<File> dataStoreRoot = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        return dataStoreRoot.map(File::toPath)
                .orElseThrow(() -> new IllegalStateException("the store started with no data store root"));
    }
}
