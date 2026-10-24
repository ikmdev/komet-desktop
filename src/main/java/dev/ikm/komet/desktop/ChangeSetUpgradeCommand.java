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
import dev.ikm.tinkar.entity.changeset.ChangeSetCompaction;
import dev.ikm.tinkar.entity.changeset.ChangeSetUpgrade;
import javafx.application.Platform;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code --kb=<store> --upgrade-change-sets[=<folder>]}: Komet with no window opens the knowledge
 * base, upgrades the change sets in its change-set folder (or the folder given) against it, prints
 * the report, and exits (IKE-Network/ike-issues#1313). JavaFX runs on its Headless platform: Komet's
 * executor needs the toolkit, the person sees nothing of it. The same steps as Tools → Upgrade Change
 * Sets…, with running the command as the confirmation: the originals that are replaced go first into
 * a dated {@code .tar.gz} in {@code ~/ike-archive}.
 *
 * <p>The report is Markdown on standard output, between {@value #REPORT_START} and
 * {@value #REPORT_END}, so a caller can find it among whatever the log writes there. Progress goes
 * to standard error. The exit status is {@value #CLEAN} when nothing is left without a pattern and
 * nothing failed, {@value #INCOMPLETE} when something is, and {@value #NOT_RUN} when the knowledge
 * base could not be opened or the arguments are wrong.
 */
public final class ChangeSetUpgradeCommand {

    /** Every change set upgraded or current. */
    public static final int CLEAN = 0;
    /** A change set left as it was, naming components the knowledge base does not hold, or one that failed. */
    public static final int INCOMPLETE = 1;
    /** Nothing done. */
    public static final int NOT_RUN = 2;

    /** The line before the report. */
    public static final String REPORT_START = "----- change-set upgrade report -----";
    /** The line after the report. */
    public static final String REPORT_END = "----- end of report -----";

    private ChangeSetUpgradeCommand() {
    }

    /**
     * The folder a store's change-set writer writes to, and so the one an upgrade reads by default.
     *
     * @param storeRoot the knowledge base's folder
     * @return {@code <store>/changeSets/src/main/resources}
     */
    public static Path changeSetFolder(Path storeRoot) {
        return storeRoot.resolve("changeSets").resolve("src").resolve("main").resolve("resources");
    }

    /** The system property naming another archive folder than {@code ~/ike-archive}. */
    public static final String ARCHIVE_PROPERTY = "komet.change-set-archive";

    /**
     * Where the originals an upgrade replaces are archived: {@code ~/ike-archive}, outside every
     * store, unless {@code -D}{@value #ARCHIVE_PROPERTY} names another folder.
     *
     * @return the folder
     */
    public static Path archiveFolder() {
        String named = System.getProperty(ARCHIVE_PROPERTY);
        return named == null || named.isBlank()
                ? Path.of(System.getProperty("user.home"), "ike-archive") : Path.of(named.strip());
    }

    /**
     * Runs the upgrade, with JavaFX on its Headless platform once the arguments check.
     *
     * @param options the launch options; {@link LaunchOptions#upgradeChangeSets()} is true
     * @param out     where the report goes
     * @param err     where progress and problems go
     * @return the exit status
     */
    public static int run(LaunchOptions options, PrintStream out, PrintStream err) {
        Optional<String> knowledgeBase = options.get(LaunchOptions.Option.KB);
        if (knowledgeBase.isEmpty()) {
            err.println("--" + LaunchOptions.UPGRADE_CHANGE_SETS_ARGUMENT + " needs the knowledge base to upgrade against: --"
                    + LaunchOptions.Option.KB.argumentName() + "=<store>");
            return NOT_RUN;
        }
        Optional<Path> folder = options.upgradeFolder().map(Path::of);
        if (folder.isPresent() && !Files.isDirectory(folder.get())) {
            err.println("No change-set folder at " + folder.get() + ".");
            return NOT_RUN;
        }
        App.initLog4J2FromConf();
        startHeadlessToolkit();
        App.initializePluginSystem();
        boolean started = false;
        try {
            started = open(knowledgeBase.get(), err);
            if (!started) {
                return NOT_RUN;
            }
            Optional<File> dataStoreRoot = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
            Path storeRoot = dataStoreRoot.map(File::toPath)
                    .orElseThrow(() -> new IllegalStateException("the store started with no data store root"));
            return upgrade(storeRoot, folder.orElse(changeSetFolder(storeRoot)), out, err);
        } catch (Exception e) {
            err.println("The upgrade stopped: " + e);
            e.printStackTrace(err);
            return NOT_RUN;
        } finally {
            if (started) {
                PrimitiveData.stop();
            }
        }
    }

    /**
     * Upgrades the folder's change sets against the running store, archives and replaces the
     * originals, and prints the report.
     *
     * @param storeRoot the running store's folder, as the report names it
     * @param folder    the change sets
     * @param out       where the report goes
     * @param err       where progress goes
     * @return the exit status
     * @throws Exception if the work cannot be written or the archive fails
     */
    static int upgrade(Path storeRoot, Path folder, PrintStream out, PrintStream err) throws Exception {
        err.println("Upgrading the change sets in " + folder + " against " + storeRoot);
        List<Path> files = ChangeSetUpgrade.changeSets(List.of(folder));
        Path work = Files.createTempDirectory("komet-change-set-upgrade-");
        ChangeSetUpgrade.Plan plan = ChangeSetUpgrade.plan(files, ChangeSetCompaction.patternsFromStore(),
                storeRoot.toString(), work,
                (index, count, changeSet) -> err.println("  " + index + " of " + count + ": " + changeSet.getFileName()));
        List<String> report = new ArrayList<>(List.of(plan.markdown()));
        boolean replacedAll = true;
        if (plan.replaceable().isEmpty()) {
            plan.discard();
        } else {
            ChangeSetUpgrade.Archive archive = plan.archive(archiveFolder(), LocalDate.now());
            ChangeSetUpgrade.Replacement replacement = plan.replace(archive);
            replacedAll = replacement.skipped().isEmpty();
            report.add(replacement.markdown());
        }
        out.println(REPORT_START);
        out.println(String.join("\n", report));
        out.println(REPORT_END);
        return plan.clean() && replacedAll ? CLEAN : INCOMPLETE;
    }

    /**
     * Starts JavaFX on its Headless glass platform: no window, no Dock icon. Komet's executor
     * reports each task's progress through the toolkit, so a store does not stop without one.
     */
    private static void startHeadlessToolkit() {
        System.setProperty("glass.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("java.awt.headless", "true");
        Platform.setImplicitExit(false);
        Platform.startup(() -> { });
    }

    /** Opens the knowledge base the way {@code --kb} does in the window, remembering nothing. */
    private static boolean open(String knowledgeBase, PrintStream err) {
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
                            + ". Close it there, then upgrade.");
                    return false;
                }
                err.println("Opening " + found.option().uri() + " with " + found.controllerName());
                SelectDataSourceController.selectDataSource(controller, found.option());
                PrimitiveData.start();
                return true;
            }
        }
    }
}
