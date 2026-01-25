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
package dev.ikm.komet.app;

import com.jpro.webapi.WebAPI;
import de.jangassen.MenuToolkit;
import dev.ikm.komet.framework.ScreenInfo;
import dev.ikm.komet.framework.graphics.LoadFonts;
import dev.ikm.komet.framework.preferences.PrefX;
import dev.ikm.komet.app.aboutdialog.BuildInfoProperties;
import dev.ikm.komet.kview.events.CreateJournalEvent;
import dev.ikm.komet.kview.events.CreateKLEditorWindowEvent;
import dev.ikm.komet.kview.events.SignInUserEvent;
import dev.ikm.komet.kview.mvvm.model.GitHubPreferencesDao;
import dev.ikm.komet.kview.mvvm.view.journal.JournalController;
import dev.ikm.komet.kview.mvvm.view.landingpage.LandingPageController;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import dev.ikm.komet.preferences.Preferences;
import dev.ikm.tinkar.common.alert.AlertObject;
import dev.ikm.tinkar.common.alert.AlertStreams;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.TinkExecutor;
import dev.ikm.tinkar.events.Evt;
import dev.ikm.tinkar.events.EvtBus;
import dev.ikm.tinkar.events.EvtBusFactory;
import dev.ikm.tinkar.events.Subscriber;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.TinkarTerm;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import one.jpro.platform.utils.CommandRunner;
import one.jpro.platform.utils.PlatformUtils;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.eclipse.collections.api.factory.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.prefs.BackingStoreException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.ikm.komet.app.AppState.LOADING_DATA_SOURCE;
import static dev.ikm.komet.app.AppState.LOGIN;
import static dev.ikm.komet.app.AppState.RUNNING;
import static dev.ikm.komet.app.AppState.SELECT_DATA_SOURCE;
import static dev.ikm.komet.app.AppState.SHUTDOWN;
import static dev.ikm.komet.app.AppState.STARTING;
import static dev.ikm.komet.app.LoginFeatureFlag.ENABLED_WEB_ONLY;
import static dev.ikm.komet.app.util.CssFile.KOMET_CSS;
import static dev.ikm.komet.app.util.CssFile.KVIEW_CSS;
import static dev.ikm.komet.app.util.CssUtils.addStylesheets;
import static dev.ikm.komet.kview.events.EventTopics.JOURNAL_TOPIC;
import static dev.ikm.komet.kview.events.EventTopics.KL_TOPIC;
import static dev.ikm.komet.kview.events.EventTopics.USER_TOPIC;
import static dev.ikm.komet.preferences.JournalWindowPreferences.JOURNALS;
import static dev.ikm.komet.preferences.JournalWindowPreferences.JOURNAL_IDS;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_TITLE;
import static dev.ikm.tinkar.events.FrameworkTopics.IMPORT_TOPIC;

/**
 * Main application class for the Komet application, extending JavaFX {@link Application}.
 * <p>
 * The {@code WebApp} class serves as the entry point for launching the Komet application,
 * which is a JavaFX-based application supporting both desktop and web platforms via JPro.
 * It manages initialization, startup, and shutdown processes, and handles various application states
 * such as starting, login, data source selection, running, and shutdown.
 * </p>
 * <p>
 * <h4>Platform-Specific Features:</h4>
 * <ul>
 *   <li><strong>Web Support:</strong> Utilizes JPro's {@link WebAPI} to support running the application in a web browser.</li>
 *   <li><strong>macOS Integration:</strong> Configures macOS-specific properties and menus.</li>
 * </ul>
 * </p>
 * <p>
 * <h4>Event Bus and Subscriptions:</h4>
 * The application uses an event bus ({@link EvtBus}) for inter-component communication. It subscribes to various events like
 * {@code CreateJournalEvent} and {@code SignInUserEvent} to handle user actions and state changes.
 * </p>
 *
 * @see Application
 * @see AppState
 * @see LoginFeatureFlag
 * @see KometPreferences
 */
public class App extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);
    public static final String ICON_LOCATION = "/icons/Komet.png";
    public static final SimpleObjectProperty<AppState> state = new SimpleObjectProperty<>(STARTING);
    public static final SimpleObjectProperty<ConceptFacade> userProperty = new SimpleObjectProperty<>();

    // Shutdown coordination flags - using AtomicBoolean for thread-safe check-and-set
    private static final Object SHUTDOWN_LOCK = new Object();
    static volatile boolean shutdownInProgress = false;
    private static volatile boolean primitiveDataStopped = false;

    static Stage primaryStage;

    WebAPI webAPI;
    static final boolean IS_BROWSER = WebAPI.isBrowser();
    static final boolean IS_DESKTOP = !IS_BROWSER && PlatformUtils.isDesktop();
    static final boolean IS_MAC = !IS_BROWSER && PlatformUtils.isMac();
    static final boolean IS_MAC_AND_NOT_TESTFX_TEST = IS_MAC && !isTestFXTest();
    final StackPane rootPane = createRootPane();
    Image appIcon;
    LandingPageController landingPageController;
    EvtBus kViewEventBus;

    AppGithub appGithub;
    AppClassicKomet appClassicKomet;
    AppMenu appMenu;
    AppPages appPages;

    /**
     * An entry point to launch the newer UI panels.
     */
    private MenuItem createJournalViewMenuItem;

    /**
     * This is a list of new windows that have been launched. During shutdown, the application close each stage gracefully.
     */
    final List<JournalController> journalControllersList = new ArrayList<>();

    /**
     * GitHub preferences data access object.
     */
    final GitHubPreferencesDao gitHubPreferencesDao = new GitHubPreferencesDao();

    /**
     * Main method that serves as the entry point for the JavaFX application.
     *
     * @param args Command line arguments for the application.
     */
    public static void main(String[] args) {
        // Launch the JavaFX application
        launch(args);
    }

    /**
     * Configures system properties specific to macOS to ensure proper integration
     * with the macOS application menu.
     */
    private static void configureMacOSProperties() {
        // Ensures that the macOS application menu is used instead of a screen menu bar
        System.setProperty("apple.laf.useScreenMenuBar", "false");

        // Sets the name of the application in the macOS application menu
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Komet");
    }

    /**
     * Adds a shutdown hook to the Java Virtual Machine (JVM) to ensure that data is saved and resources
     * are released gracefully before the application exits.
     * This method should be called during the application's initialization phase to ensure that the shutdown
     * hook is registered before the application begins execution. By doing so, it guarantees that critical
     * cleanup operations are performed even if the application is terminated unexpectedly.
     */
    private static void addShutdownHook() {
    LOG.info("Adding shutdown hook");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        LOG.info("Starting shutdown hook");
        
        // Synchronize to prevent concurrent execution with quit()
        synchronized (SHUTDOWN_LOCK) {
            // Only run shutdown hook cleanup if quit() hasn't already been called
            if (shutdownInProgress) {
                LOG.info("quit() already in progress - shutdown hook exiting early");
                return;
            }
            
            // Mark that we're shutting down
            shutdownInProgress = true;
        }
        
        // Only stop PrimitiveData in the shutdown hook if it hasn't been stopped already
        // Use synchronized block to ensure atomic check-and-set
        synchronized (SHUTDOWN_LOCK) {
            if (!primitiveDataStopped) {
                LOG.info("PrimitiveData not yet stopped - performing emergency shutdown");
                primitiveDataStopped = true; // Set BEFORE calling stop()
                try {
                    PrimitiveData.stop();
                    LOG.info("PrimitiveData stopped in shutdown hook");
                } catch (Throwable t) {
                    LOG.error("Error stopping PrimitiveData in shutdown hook", t);
                }
            } else {
                LOG.info("PrimitiveData already stopped - shutdown hook skipping");
            }
        }
    }, "Komet-Shutdown-Hook"));
}
    public static void initLog4J2FromConf() {
        // Resolve <bin directory>/../conf/xml (or your app’s working dir/../conf)
        Path cfg = Path.of("..", "conf", "log4j2.xml").toAbsolutePath();

        // Initialize BEFORE any logger is used
        LoggerContext context = Configurator.initialize("app-log4j2", null, cfg.toUri());
        context.getLogger("console").info("Log4j2 configuration loaded from: {}", cfg);
        context.getLogger("rollingFile").info("Log4j2 configuration loaded from: {}", cfg);
        System.out.println(context.getConfiguration());
    }

    private static void logStartupInfo() {
        BuildInfoProperties buildInfo = new BuildInfoProperties();
        String buildVersion = buildInfo.getBuildVersion();
        String mavenVersion = buildInfo.getMavenVersion();
        String version = (buildVersion != null && !buildVersion.isBlank() && !buildVersion.equals("${build.version}"))
                ? buildVersion
                : mavenVersion;

        LOG.info("Komet version: {}", version);
        LOG.info("Build time: {}", buildInfo.getBuildTime());
        LOG.info("Runtime: java={}, javafx={}, os={}, arch={}, platform={}",
                System.getProperty("java.version"),
                System.getProperty("javafx.runtime.version"),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                IS_BROWSER ? "browser" : (IS_DESKTOP ? "desktop" : "unknown"));

        logModuleJars();
    }

    private static void logModuleJars() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("module-jars"));
        candidates.add(Path.of("..", "module-jars"));

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaHomePath = Path.of(javaHome);
            candidates.add(javaHomePath.resolve("module-jars"));
            candidates.add(javaHomePath.resolve("..").resolve("module-jars"));
            candidates.add(javaHomePath.resolve("..").resolve("..").resolve("module-jars"));
        }

        for (Path dir : candidates) {
            if (!Files.isDirectory(dir)) {
                continue;
            }

            LOG.info("module-jars directory: {}", dir.toAbsolutePath());
            try (Stream<Path> stream = Files.list(dir)) {
                List<String> jarNames = stream
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
                if (jarNames.isEmpty()) {
                    LOG.info("module-jars is empty");
                } else {
                    LOG.info("module-jars contents ({}): {}", jarNames.size(), String.join(", ", jarNames));
                }
            } catch (IOException e) {
                LOG.warn("Failed to list module-jars contents from {}", dir.toAbsolutePath(), e);
            }
            return;
        }

        LOG.info("module-jars directory not found in working directory or parent");
    }

    @Override
    public void init() throws Exception {
        initLog4J2FromConf();

        LOG.info("Starting Komet");
        logStartupInfo();

        // Set system properties for macOS look and feel and application name in the menu bar
        configureMacOSProperties();

        // Add a shutdown hook to ensure resources are saved and properly cleaned up before the application exits
        addShutdownHook();

        // Load custom fonts required by the application
        LoadFonts.load();

        // Load the application icon
        appIcon = new Image(App.class.getResource(ICON_LOCATION).toString(), true);

        // Initialize the event bus for inter-component communication
        kViewEventBus = EvtBusFactory.getInstance(EvtBus.class);

        // Create a subscriber for handling CreateJournalEvent
        Subscriber<CreateJournalEvent> detailsSubscriber = evt -> {
            final PrefX journalWindowSettingsObjectMap = evt.getWindowSettingsObjectMap();
            final UUID journalTopic = journalWindowSettingsObjectMap.getValue(JOURNAL_TOPIC);
            final String journalName = journalWindowSettingsObjectMap.getValue(JOURNAL_TITLE);
            // Check if a journal window with the same title is already open
            journalControllersList.stream()
                    .filter(journalController ->
                            journalController.getJournalTopic().equals(journalTopic))
                    .findFirst()
                    .ifPresentOrElse(journalController -> {
                                if (IS_BROWSER) {
                                    // Similar to the desktop version, bring the existing tab to the front
                                    Stage journalStage = (Stage) journalController.getJournalBorderPane().getScene().getWindow();
                                    webAPI.openStageAsTab(journalStage, journalName.replace(" ", "_"));
                                } else {
                                    // Bring the existing window to the front
                                    journalController.windowToFront();
                                }
                            },
                            () -> appPages.launchJournalViewPage(journalWindowSettingsObjectMap, userProperty.get()));
        };

        // Subscribe the subscriber to the JOURNAL_TOPIC
        kViewEventBus.subscribe(JOURNAL_TOPIC, CreateJournalEvent.class, detailsSubscriber);

        // Create a subscriber for handling KL Window Event
        Subscriber<CreateKLEditorWindowEvent> createKLEditorWindowEventSubscriber = evt -> {
            final PrefX journalWindowSettingsObjectMap = evt.getWindowSettingsObjectMap();
            appPages.launchKLEditorViewPage(journalWindowSettingsObjectMap, userProperty.get(), evt.getWindowToLoad());
        };

        // Subscribe the subscriber to the KL_TOPIC
        kViewEventBus.subscribe(KL_TOPIC, CreateKLEditorWindowEvent.class, createKLEditorWindowEventSubscriber);

        Subscriber<SignInUserEvent> signInUserEventSubscriber = evt -> {
            final ConceptFacade loggedInUser = (ConceptFacade) evt.getLoggedInUser();
            userProperty.set(loggedInUser);

            if (state.get() == RUNNING) {
                appPages.launchLandingPage(primaryStage, loggedInUser);
            } else {
                state.set(AppState.SELECT_DATA_SOURCE);
                state.addListener(this::appStateChangeListener);
                appPages.launchSelectDataSourcePage(primaryStage);
            }
        };

        // Subscribe the subscriber to the USER_TOPIC
        kViewEventBus.subscribe(USER_TOPIC, SignInUserEvent.class, signInUserEventSubscriber);

        //Pops up the import dialog window on any events received on the IMPORT_TOPIC
        Subscriber<Evt> importSubscriber = _ -> {
            appMenu.openImport(primaryStage);
        };
        kViewEventBus.subscribe(IMPORT_TOPIC, Evt.class, importSubscriber);
    }

    @Override
    public void start(Stage stage) {
        appGithub = new AppGithub(this);
        appClassicKomet = new AppClassicKomet(this);
        appMenu = new AppMenu(this);
        appPages = new AppPages(this);

        try {
            App.primaryStage = stage;
            Thread.currentThread().setUncaughtExceptionHandler((thread, exception) ->
                    AlertStreams.getRoot().dispatch(AlertObject.makeError(exception)));

            // Initialize the JPro WebAPI
            if (IS_BROWSER) {
                webAPI = WebAPI.getWebAPI(stage);
            }

            // Set the application icon
            stage.getIcons().setAll(appIcon);

            Scene scene = new Scene(rootPane);
            addStylesheets(scene, KOMET_CSS, KVIEW_CSS);
            stage.setScene(scene);

            // Handle the login feature based on the platform and the provided feature flag
            handleLoginFeature(ENABLED_WEB_ONLY, stage);

            addEventFilters(stage);

            // This is called only when the user clicks the close button on the window
            stage.setOnCloseRequest(windowEvent -> state.set(SHUTDOWN));

            // Show stage and set initial state
            stage.show();
        } catch (Exception ex) {
            LOG.error("Failed to initialize the application", ex);
            Platform.exit();
        }
    }

    /**
     * Handles the login feature based on the provided {@link LoginFeatureFlag} and platform.
     *
     * @param loginFeatureFlag the current state of the login feature
     * @param stage            the current application stage
     */
    public void handleLoginFeature(LoginFeatureFlag loginFeatureFlag, Stage stage) {
        switch (loginFeatureFlag) {
            case ENABLED_WEB_ONLY -> {
                if (IS_BROWSER) {
                    startLogin(stage);
                } else {
                    startSelectDataSource(stage);
                }
            }
            case ENABLED_DESKTOP_ONLY -> {
                if (IS_DESKTOP) {
                    startLogin(stage);
                } else {
                    startSelectDataSource(stage);
                }
            }
            case ENABLED -> startLogin(stage);
            case DISABLED -> startSelectDataSource(stage);
        }
    }

    /**
     * Initiates the login process by setting the application state to {@link AppState#LOGIN}
     * and launching the login page.
     *
     * @param stage the current application stage
     */
    private void startLogin(Stage stage) {
        state.set(LOGIN);
        appPages.launchLoginPage(stage);
    }

    /**
     * Initiates the data source selection process by setting the application state
     * to {@link AppState#SELECT_DATA_SOURCE}, adding a state change listener, and launching
     * the data source selection page.
     *
     * @param stage the current application stage
     */
    private void startSelectDataSource(Stage stage) {
        state.set(SELECT_DATA_SOURCE);
        state.addListener(this::appStateChangeListener);
        appPages.launchSelectDataSourcePage(stage);
    }

    @Override
    public void stop() {
        LOG.info("Stopping application\n\n###############\n\n");

        // Set shutdown flag to prevent further state transitions
        shutdownInProgress = true;

        appGithub.disconnectFromGithub();

        if (IS_DESKTOP) {
            // close all journal windows
            // Immutable copy, since we are getting: ConcurrentModificationException
            Lists.immutable.ofAll(journalControllersList).forEach(JournalController::close);
        }
    }

    private StackPane createRootPane(Node... children) {
        return new StackPane(children) {

            @Override
            protected void layoutChildren() {
                if (IS_BROWSER) {
                    Scene scene = primaryStage.getScene();
                    if (scene != null) {
                        webAPI.layoutRoot(scene);
                    }
                }
                super.layoutChildren();
            }
        };
    }

    /**
     * Checks if the application is being tested using TestFX Framework.
     *
     * @return {@code true} if testing mode; {@code false} otherwise.
     */
    private static boolean isTestFXTest() {
        String testFxTest = System.getProperty("testfx.headless");
        return testFxTest != null && !testFxTest.isBlank();
    }


    private void addEventFilters(Stage stage) {
        stage.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            ScreenInfo.mouseIsPressed(true);
            ScreenInfo.mouseWasDragged(false);
        });
        stage.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            ScreenInfo.mouseIsPressed(false);
            ScreenInfo.mouseIsDragging(false);
        });
        stage.addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
            ScreenInfo.mouseIsDragging(true);
            ScreenInfo.mouseWasDragged(true);
        });
    }


    /**
     * Saves the current state of the journals and its windows to the application's preferences system.
     * <p>
     * This method persists all journal-related data, including:
     * <ul>
     *   <li>All open window states (via {@link JournalController#saveWindows(KometPreferences)})</li>
     *   <li>Journal metadata (topic UUID, title, directory name)</li>
     *   <li>Window geometry (width, height, x/y position)</li>
     *   <li>Author information</li>
     *   <li>Last edit timestamp</li>
     * </ul>
     * <p>
     * The preferences are stored in a hierarchical structure:
     * <pre>
     * Root Configuration Preferences
     *   └── journals
     *       └── [journal_shortened-UUID]
     *           ├── Journal metadata (UUID, title, dimensions, position, etc.)
     *           └── Window states
     * </pre>
     */
    public void saveJournalWindowsToPreferences() {
        final KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        final KometPreferences journalsPreferences = appPreferences.node(JOURNALS);

        // Non launched journal windows should be preserved.
        List<String> journalSubWindowFoldersFromPref = journalsPreferences.getList(JOURNAL_IDS);

        // launched (journal Controllers List) will overwrite existing window preferences.
        List<String> journalSubWindowFolders = new ArrayList<>(journalControllersList.size());
        for (JournalController controller : journalControllersList) {
            String journalSubWindowPrefFolder = controller.getJournalDirName();
            journalSubWindowFolders.add(journalSubWindowPrefFolder);

            final KometPreferences journalSubWindowPreferences = appPreferences.node(JOURNALS +
                    File.separator + journalSubWindowPrefFolder);
            controller.saveWindows(journalSubWindowPreferences);
        }

        // Make sure windows that are not summoned will not be deleted (not added to JOURNAL_NAMES)
        for (String x : journalSubWindowFolders) {
            if (!journalSubWindowFoldersFromPref.contains(x)) {
                journalSubWindowFoldersFromPref.add(x);
            }
        }
        journalsPreferences.putList(JOURNAL_IDS, journalSubWindowFoldersFromPref);

        try {
            journalsPreferences.flush();
            appPreferences.flush();
            appPreferences.sync();
        } catch (BackingStoreException e) {
            LOG.error("error writing journal window flag to preferences", e);
        }
    }

    private void appStateChangeListener(ObservableValue<? extends AppState> observable, AppState oldValue, AppState newValue) {
        LOG.info("App state transition: {} → {}", oldValue, newValue);

        // Prevent state transitions if shutdown is already in progress
        if (shutdownInProgress && newValue != SHUTDOWN) {
            LOG.warn("Ignoring state transition to {} because shutdown is in progress", newValue);
            return;
        }

        try {
            switch (newValue) {
                case SELECTED_DATA_SOURCE -> {
                    LOG.info("Transitioning to LOADING_DATA_SOURCE");
                    Platform.runLater(() -> state.set(LOADING_DATA_SOURCE));
                    TinkExecutor.threadPool().submit(new LoadDataSourceTask(state));
                }
                case SELECT_USER -> {
                    LOG.info("Launching login author screen");
                    appPages.launchLoginAuthor(primaryStage);
                    LOG.info("Login author screen launched successfully");
                }
                case RUNNING -> {
                    LOG.info("App entering RUNNING state");
                    if (userProperty.get() == null) {
                        //If user property is not set then use the TinkarTerm.User concept.
                        LOG.info("No user set, using TinkarTerm.USER");
                        userProperty.set(TinkarTerm.USER);
                    }
                    LOG.info("Launching landing page for user: {}", userProperty.get());
                    appPages.launchLandingPage(primaryStage, (ConceptFacade) userProperty.get());
                    LOG.info("Landing page launched successfully");
                }
                case SHUTDOWN -> {
                    LOG.info("App shutting down");
                    quit();
                }
            }
        } catch (Throwable e) {
            LOG.error("Error during state change from {} to {}", oldValue, newValue, e);
            // Perform critical data cleanup before exit (skip UI cleanup which may be unsafe)
            try {
                stopDataServices();
            } catch (Exception cleanupException) {
                LOG.error("Error during emergency data cleanup", cleanupException);
            }

            // Give background threads time to complete
            try {
                LOG.info("Waiting for background threads to settle");
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // Don't call Platform.exit() - it can cause native crashes during error handling
            // Instead, call System.exit() to terminate cleanly without JavaFX shutdown
            LOG.info("Calling System.exit(1) - data already saved, skipping JavaFX shutdown");
            System.exit(1);
        }
    }

    /**
     * Stops data services (PrimitiveData and Preferences).
     * Safe to call multiple times.
     */
    private void stopDataServices() {
        // Use a local flag to determine if we should actually stop
        boolean shouldStop = false;
        
        // Synchronize ONLY for the flag check - don't hold lock during stop()
        synchronized (SHUTDOWN_LOCK) {
            if (!primitiveDataStopped) {
                primitiveDataStopped = true; // Set BEFORE calling stop()
                shouldStop = true;
            }
        }
        
        // Perform the actual stop OUTSIDE the synchronized block
        if (shouldStop) {
            LOG.info(">>> Stopping PrimitiveData");
            try {
                PrimitiveData.stop();
                LOG.info(">>> PrimitiveData stopped");
            } catch (Throwable t) {
                LOG.error(">>> Error stopping PrimitiveData", t);
            }
        } else {
            LOG.info(">>> PrimitiveData already stopped - skipping");
        }

        LOG.info(">>> Stopping Preferences");
        try {
            Preferences.stop();
            LOG.info(">>> Preferences stopped");
        } catch (Throwable t) {
            LOG.error(">>> Error stopping Preferences", t);
        }
    }

    public void quit() {
    LOG.info(">>> quit() called - thread: {}", Thread.currentThread().getName());
    
    // Prevent re-entrant calls using synchronized block
    synchronized (SHUTDOWN_LOCK) {
        if (shutdownInProgress) {
            LOG.warn(">>> quit() already in progress, ignoring duplicate call");
            return;
        }
        shutdownInProgress = true;
    }

    saveJournalWindowsToPreferences();
    LOG.info(">>> Saved journal windows to preferences");

    stopDataServices();

    // Note: Removed explicit macOS MenuToolkit cleanup as it conflicts with
    // Platform.exit()'s own native shutdown, causing SIGSEGV in objc_msgSend.
    // Platform.exit() will handle all JavaFX/native cleanup automatically.

    LOG.info(">>> Calling Platform.exit()");

    // Exit JavaFX - but do it synchronously if we're already on FX thread
    if (Platform.isFxApplicationThread()) {
        LOG.info(">>> Already on FX thread, calling Platform.exit()");
        Platform.exit();
        
        // Give Platform.exit() time to clean up before stopping server
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    } else {
        LOG.info(">>> Scheduling Platform.exit() on FX thread");
        CountDownLatch exitLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            LOG.info(">>> Platform.exit() executing on FX thread");
            Platform.exit();
            exitLatch.countDown();
        });

        // Wait for Platform.exit() to complete
        try {
            exitLatch.await();
            LOG.info(">>> Platform.exit() completed");
            Thread.sleep(200); // Give it time to clean up
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    LOG.info(">>> Calling stopServer()");
    stopServer();
    LOG.info(">>> quit() method complete");
}

    /**
     * Stops the JPro server by running the stop script.
     */
    public void stopServer() {
        if (IS_BROWSER) {
            LOG.info("Stopping JPro server...");
            final String jproMode = WebAPI.getServerInfo().getJProMode();
            final String[] stopScriptArgs;
            final CommandRunner stopCommandRunner;
            final File workingDir;
            if ("dev".equalsIgnoreCase(jproMode)) {
                workingDir = new File(System.getProperty("user.dir")).getParentFile();
                stopScriptArgs = PlatformUtils.isWindows() ?
                        new String[]{"cmd", "/c", "mvnw.bat", "-f", "application", "-Pjpro", "jpro:stop"} :
                        new String[]{"bash", "./mvnw", "-f", "application", "-Pjpro", "jpro:stop"};
                stopCommandRunner = new CommandRunner(stopScriptArgs);
            } else {
                workingDir = new File("bin");
                final String scriptExtension = PlatformUtils.isWindows() ? ".bat" : ".sh";
                final String stopScriptName = "stop" + scriptExtension;
                stopScriptArgs = PlatformUtils.isWindows() ?
                        new String[]{"cmd", "/c", stopScriptName} : new String[]{"bash", stopScriptName};
                stopCommandRunner = new CommandRunner(stopScriptArgs);
            }
            try {
                stopCommandRunner.setPrintToConsole(true);
                final int exitCode = stopCommandRunner.run("jpro-stop", workingDir);
                if (exitCode == 0) {
                    LOG.info("JPro server stopped successfully.");
                } else {
                    LOG.error("Failed to stop JPro server. Exit code: {}", exitCode);
                }
            } catch (IOException | InterruptedException ex) {
                LOG.error("Error stopping the server", ex);
            }
        }
    }

    public enum AppKeys {
        APP_INITIALIZED
    }
}
