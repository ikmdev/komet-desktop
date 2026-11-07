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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.importer.registry.ChangeSetLoad;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code --kb=<store> --load-change-sets}: Komet with no window opens the knowledge base, loads
 * the change sets in its change-set folders that the store has not registered, registers each,
 * prints the report, and exits ({@code dev-ike-dashboard}, decision 20). The same load every open
 * of the store runs, on its own: the validation step of the dashboard's upgrade, which carries a
 * store's change sets into a new build and asks Komet to load what the new build has not
 * registered before swapping it in.
 *
 * <p>The report is Markdown on standard output, between {@value #REPORT_START} and
 * {@value #REPORT_END}, so a caller can find it among whatever the log writes there. Progress goes
 * to standard error. The exit status is {@value #CLEAN} when every file is in the store,
 * {@value #INCOMPLETE} when a file was refused, and {@value #NOT_RUN} when the knowledge base
 * could not be opened or the arguments are wrong.
 */
public final class ChangeSetLoadCommand {

    /** Every change-set file loaded, now or before. */
    public static final int CLEAN = 0;
    /** A change-set file refused: it names a component the knowledge base does not hold, or failed to load. */
    public static final int INCOMPLETE = 1;
    /** Nothing done. */
    public static final int NOT_RUN = 2;

    /** The line before the report. */
    public static final String REPORT_START = "----- change-set load report -----";
    /** The line after the report. */
    public static final String REPORT_END = "----- end of report -----";

    private ChangeSetLoadCommand() {
    }

    /**
     * Runs the load, with JavaFX on its Headless platform once the arguments check.
     *
     * @param options the launch options; {@link LaunchOptions#loadChangeSets()} is true
     * @param out     where the report goes
     * @param err     where progress and problems go
     * @return the exit status
     */
    public static int run(LaunchOptions options, PrintStream out, PrintStream err) {
        Optional<String> knowledgeBase = options.get(LaunchOptions.Option.KB);
        if (knowledgeBase.isEmpty()) {
            err.println("--" + LaunchOptions.LOAD_CHANGE_SETS_ARGUMENT + " needs the knowledge base to load into: --"
                    + LaunchOptions.Option.KB.argumentName() + "=<store>");
            return NOT_RUN;
        }
        HeadlessStore.startPlatform();
        boolean started = false;
        try {
            started = HeadlessStore.open(knowledgeBase.get(), err, "load");
            if (!started) {
                return NOT_RUN;
            }
            return load(HeadlessStore.storeRoot(), out, err);
        } catch (Exception e) {
            err.println("The load stopped: " + e);
            e.printStackTrace(err);
            return NOT_RUN;
        } finally {
            if (started) {
                PrimitiveData.stop();
            }
        }
    }

    /**
     * Loads the store's unregistered change sets and prints the report.
     *
     * @param storeRoot the running store's folder
     * @param out       where the report goes
     * @param err       where progress goes
     * @return the exit status
     * @throws Exception if a folder cannot be listed
     */
    static int load(Path storeRoot, PrintStream out, PrintStream err) throws Exception {
        err.println("Loading the change sets of " + storeRoot + " that the store has not registered");
        ChangeSetLoad.Report report = ChangeSetLoad.loadStore(storeRoot, (index, count, file, retry) ->
                err.println("  " + (retry ? "retrying " : "") + index + " of " + count + ": " + file.getFileName()));
        out.println(REPORT_START);
        out.println(report.markdown());
        out.println(REPORT_END);
        return report.clean() ? CLEAN : INCOMPLETE;
    }
}
