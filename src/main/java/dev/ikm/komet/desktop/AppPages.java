package dev.ikm.komet.desktop;

import static dev.ikm.komet.desktop.App.IS_BROWSER;
import static dev.ikm.komet.desktop.AppState.SHUTDOWN;
import static dev.ikm.komet.desktop.util.CssFile.ICONS;
import static dev.ikm.komet.desktop.util.CssFile.KLCORE_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KLEDITOR_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KLEDITOR_WINDOW_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KOMET_CSS;
import static dev.ikm.komet.desktop.util.CssFile.KVIEW_CSS;
import static dev.ikm.komet.desktop.util.CssUtils.addStylesheets;
import static dev.ikm.komet.kview.events.EventTopics.JOURNAL_TOPIC;
import static dev.ikm.komet.kview.events.JournalTileEvent.UPDATE_JOURNAL_TILE;
import static dev.ikm.komet.kview.mvvm.view.loginauthor.LoginAuthorViewModel.LoginProperties.SELECTED_AUTHOR;
import static dev.ikm.komet.kview.mvvm.viewmodel.ViewModelKey.CURRENT_JOURNAL_WINDOW_TOPIC;
import static dev.ikm.komet.kview.mvvm.viewmodel.ViewModelKey.VIEW_PROPERTIES;
import static dev.ikm.komet.kview.mvvm.viewmodel.JournalViewModel.WINDOW_SETTINGS;
import static dev.ikm.komet.preferences.JournalWindowPreferences.AUTHOR_LOGIN_WINDOW;
import static dev.ikm.komet.preferences.JournalWindowPreferences.DEFAULT_JOURNAL_HEIGHT;
import static dev.ikm.komet.preferences.JournalWindowPreferences.DEFAULT_JOURNAL_WIDTH;
import static dev.ikm.komet.preferences.JournalWindowPreferences.MAIN_KOMET_WINDOW;
import static dev.ikm.komet.preferences.JournalWindowSettings.CAN_DELETE;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_DIR_NAME;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_HEIGHT;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_WIDTH;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_XPOS;
import static dev.ikm.komet.preferences.JournalWindowSettings.JOURNAL_YPOS;
import static dev.ikm.komet.preferences.JournalWindowSettings.PARENT_VIEW_COORDINATES;
import static dev.ikm.komet.preferences.KLEditorPreferences.KL_EDITOR_APP;
import static dev.ikm.komet.preferences.KLEditorPreferences.KL_STANDARD_WINDOWS_DIR;
import static dev.ikm.komet.preferences.KLEditorPreferences.KL_USER_WINDOWS_DIR;
import static javafx.scene.layout.Region.USE_COMPUTED_SIZE;
import dev.ikm.komet.framework.KometNodeFactory;
import dev.ikm.komet.framework.observable.read.NavigationReads;
import dev.ikm.komet.framework.preferences.PrefX;
import dev.ikm.komet.framework.view.ObservableEditCoordinate;
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.framework.window.WindowSettings;
import dev.ikm.komet.kleditorapp.view.KLEditorMainScreenController;
import dev.ikm.komet.kview.events.JournalTileEvent;
import dev.ikm.komet.kview.mvvm.model.JournalNames;
import dev.ikm.komet.kview.mvvm.model.ViewCoordinateHelper;
import dev.ikm.komet.kview.mvvm.view.KViewResources;
import dev.ikm.komet.kview.mvvm.view.journal.JournalController;
import dev.ikm.komet.kview.mvvm.view.landingpage.LandingPageViewFactory;
import dev.ikm.komet.kview.mvvm.view.login.LoginPageController;
import dev.ikm.komet.kview.mvvm.view.loginauthor.LoginAuthorController;
import dev.ikm.komet.kview.mvvm.view.loginauthor.LoginAuthorViewModel;
import dev.ikm.komet.kview.mvvm.viewmodel.JournalViewModel;
import dev.ikm.komet.navigator.graph.GraphNavigatorNodeFactory;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import dev.ikm.komet.search.SearchNodeFactory;
import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceExclusionGroup;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.TinkarTerm;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.carlfx.cognitive.loader.Config;
import org.carlfx.cognitive.loader.FXMLMvvmLoader;
import org.carlfx.cognitive.loader.JFXNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class AppPages {
    private static final Logger LOG = LoggerFactory.getLogger(AppPages.class);

    private final App app;

    /**
     * Property dev_author is a property passed into the system to bypass the user login screen.
     * -Ddev_author=Gretel
     * -Ddev_author=1c0023ed-559e-3311-9e55-bd4bd9e5628f
     */
    private final static String DEV_AUTHOR = "dev_author";

    public AppPages(App app) {
        this.app = app;
    }

    void launchLoginPage(Stage stage) {
        JFXNode<BorderPane, Void> loginNode = FXMLMvvmLoader.make(
                LoginPageController.class.getResource("login-page.fxml"));
        BorderPane loginPane = loginNode.node();
        app.rootPane.getChildren().setAll(loginPane);
        stage.setTitle("KOMET Login");

        app.appMenu.setupMenus(loginPane);
    }

    void launchSelectDataSourcePage(Stage stage) {
        try {
            // Completing a 6-bit → 8-bit migration: propose the new folder (ike-issues#1138).
            NidLayoutMigration.applyPendingMigration();
            FXMLLoader sourceLoader = new FXMLLoader(getClass().getResource("SelectDataSource.fxml"));
            BorderPane sourceRoot = sourceLoader.load();
            SelectDataSourceController sourceController = sourceLoader.getController();
            sourceController.getCancelButton().setOnAction(actionEvent -> {
                // Exit the application if the user cancels the data source selection
                Platform.exit();
                app.stopServer();
            });
            app.rootPane.getChildren().setAll(sourceRoot);
            stage.setTitle("KOMET Startup");

            app.appMenu.setupMenus(app.rootPane);
        } catch (IOException ex) {
            LOG.error("Failed to initialize the select data source window", ex);
        }
    }

    void launchLoginAuthor(Stage stage){
        final KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        final KometPreferences windowPreferences = appPreferences.node(AUTHOR_LOGIN_WINDOW);
        final WindowSettings windowSettings = new WindowSettings(windowPreferences);
        ViewProperties viewProperties = windowSettings.getView().makeOverridableViewProperties("login-author");

        // Bypass the login screen for -Ddev_author=<username or uuid string>, or for --user with a
        // password that checks; otherwise a --user author is preselected on the screen.
        final ConceptEntity[] preselectedAuthor = new ConceptEntity[1];
        if (bypassLogin(viewProperties, author -> preselectedAuthor[0] = author)) {
            return;
        }

        Config loginConfig = new Config(LoginAuthorController.class.getResource("LoginAuthor.fxml"))
                .updateViewModel("loginAuthorViewModel", loginAuthorViewModel -> {
                    loginAuthorViewModel.setPropertyValue(VIEW_PROPERTIES, viewProperties);
                    if (preselectedAuthor[0] != null) {
                        loginAuthorViewModel.setPropertyValue(SELECTED_AUTHOR, preselectedAuthor[0]);
                    }
                });


        JFXNode<StackPane, LoginAuthorController> journalJFXNode = FXMLMvvmLoader.make(loginConfig);
        StackPane authorLoginBorderPane = journalJFXNode.node();
        stage.getIcons().setAll(app.appIcon);
        stage.setTitle("KOMET Author selection");
        stage.setWidth(authorLoginBorderPane.prefWidth(USE_COMPUTED_SIZE));
        stage.setHeight(authorLoginBorderPane.prefHeight(USE_COMPUTED_SIZE));
        app.rootPane.getChildren().setAll(authorLoginBorderPane);

        LoginAuthorController loginAuthorController = journalJFXNode.controller();

        loginAuthorController.onLogin().thenAccept(loginAuthorViewModel -> {
            ConceptEntity userConceptEntity = loginAuthorViewModel.getPropertyValue(SELECTED_AUTHOR);
            ConceptFacade loggedInUser = ConceptFacade.make(userConceptEntity.nid());
            App.userProperty.set(loggedInUser);
            App.state.set(AppState.RUNNING);
        });
    }

    /**
     * Logs in without the author screen when the launch asked for it, and returns true if it did:
     * the developer bypass {@code -Ddev_author=<name or uuid>} with no password, or
     * {@code --user=<name or uuid>} with the password the author screen would accept
     * (IKE-Network/ike-issues#1139). When {@code --user} names an author but the password is
     * missing or does not check, the author screen opens with that author selected.
     *
     * @param viewProperties the author screen's view
     * @param preselect      receives the {@code --user} author when the screen must still be shown
     * @return true if an author is logged in and the app has moved to {@link AppState#RUNNING}
     */
    private boolean bypassLogin(ViewProperties viewProperties, Consumer<ConceptEntity> preselect) {
        // Create new instance of ViewCalculator to have stated navigation along with inferred.
        ViewCalculator viewCalculator = ViewCoordinateHelper.createNavigationCalculatorWithPatternNidsLatest(viewProperties, TinkarTerm.STATED_NAVIGATION_PATTERN.nid());

        // Developer bypass using a known user, no password.
        String devAuthorPropStr = System.getProperty(DEV_AUTHOR);
        if (devAuthorPropStr != null) {
            Optional<ConceptEntity> devAuthor = resolveAuthor(viewCalculator, devAuthorPropStr);
            if (devAuthor.isPresent()) {
                LOG.info("Developer By Pass {} = {}, name = {}", DEV_AUTHOR, devAuthorPropStr, viewCalculator.getDescriptionTextOrNid(devAuthor.get().nid()));
                logIn(devAuthor.get());
                return true;
            }
            // Developer entered a non existing user
            LOG.warn("No concept entity found for user id {}. Will be showing login screen.", devAuthorPropStr);
        }

        // --user: the password is checked the way the author screen checks it.
        Optional<String> launchUser = LaunchOptions.current().get(LaunchOptions.Option.USER);
        if (launchUser.isPresent()) {
            Optional<ConceptEntity> author = resolveAuthor(viewCalculator, launchUser.get());
            if (author.isEmpty()) {
                LOG.warn("--{} names no author: {}. Showing the author screen.",
                        LaunchOptions.Option.USER.argumentName(), launchUser.get());
                return false;
            }
            Optional<String> password = LaunchOptions.current().password(System::getenv);
            if (password.isPresent() && LoginAuthorViewModel.passwordMatches(viewProperties, author.get(), password.get())) {
                LOG.info("Logged in from the command line as {}", viewCalculator.getDescriptionTextOrNid(author.get().nid()));
                logIn(author.get());
                return true;
            }
            if (password.isEmpty()) {
                LOG.warn("--{} {}: no password supplied (set {} or pass --{}). Showing the author screen.",
                        LaunchOptions.Option.USER.argumentName(), launchUser.get(),
                        LaunchOptions.PASSWORD_ENVIRONMENT_VARIABLE, LaunchOptions.Option.PASSWORD_FILE.argumentName());
            } else {
                LOG.warn("--{} {}: the password does not match. Showing the author screen.",
                        LaunchOptions.Option.USER.argumentName(), launchUser.get());
            }
            preselect.accept(author.get());
        }
        return false;
    }

    /**
     * Finds the author a launch option names, by public id or by name, among the authors the
     * author screen lists: the leaf descendants of {@link TinkarTerm#USER}, else that concept itself.
     *
     * @param viewCalculator a calculator with stated navigation
     * @param nameOrUuid     the author's name or one of its UUIDs
     * @return the author, or empty when none matches
     */
    private static Optional<ConceptEntity> resolveAuthor(ViewCalculator viewCalculator, String nameOrUuid) {
        // Only leaf descendants of USER are named users; grouping concepts in the subtree are excluded (ike-issues#754).
        Set<ConceptEntity> authors = NavigationReads.leafDescendantsOf(viewCalculator, TinkarTerm.USER);
        if (authors.isEmpty()) {
            // add default user into set of available users
            authors.add(EntityService.get().getEntityFast(TinkarTerm.USER));
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ex) {
            // a name, not a UUID
        }
        final UUID givenUuid = uuid;
        return authors.stream().filter(author -> {
            if (givenUuid != null) {
                return author.publicId().contains(givenUuid);
            }
            return nameOrUuid.equals(viewCalculator.getPreferredDescriptionTextWithFallbackOrNid(author.nid()))
                    || viewCalculator.getDescriptionText(author.nid()).map(nameOrUuid::equals).orElse(false);
        }).findFirst();
    }

    private static void logIn(ConceptEntity author) {
        App.userProperty.set(author.toProxy());
        App.state.set(AppState.RUNNING);
    }

    /**
     * Opens the knowledge base named by {@code --kb} with no picker, and returns true if it did
     * (IKE-Network/ike-issues#1139). It opens only what the picker could open, through the same
     * provider and the same {@link SelectDataSourceController#prepareDataSource} step. When the
     * knowledge base cannot be resolved, or is open in another process, the reason is shown and
     * the caller opens the picker instead.
     *
     * @param stage the primary stage
     * @return true if the knowledge base is loading
     */
    boolean openLaunchKnowledgeBase(Stage stage) {
        Optional<String> knowledgeBase = LaunchOptions.current().get(LaunchOptions.Option.KB);
        if (knowledgeBase.isEmpty()) {
            return false;
        }
        ServiceLifecycleManager lifecycleManager = ServiceLifecycleManager.get();
        if (!lifecycleManager.isDiscovered()) {
            lifecycleManager.discoverServices();
        }
        List<DataServiceController<?>> controllers = new ArrayList<>();
        lifecycleManager.getServicesForGroup(ServiceExclusionGroup.DATA_PROVIDER)
                .forEach(service -> controllers.add((DataServiceController<?>) service));

        Path home = Path.of(System.getProperty("user.home"));
        KnowledgeBaseResolver.Resolution resolution = KnowledgeBaseResolver.resolve(knowledgeBase.get(),
                home.resolve("Solor"), home, KnowledgeBaseResolver.candidates(controllers));
        switch (resolution) {
            case KnowledgeBaseResolver.Unresolved unresolved -> {
                LOG.error("--{}={}: {}", LaunchOptions.Option.KB.argumentName(), knowledgeBase.get(), unresolved.message());
                showLaunchProblem("Cannot open the knowledge base", unresolved.message());
                return false;
            }
            case KnowledgeBaseResolver.Found found -> {
                DataServiceController<?> controller = controllers.stream()
                        .filter(candidate -> candidate.controllerName().equals(found.controllerName()))
                        .findFirst().orElseThrow();
                Optional<String> conflict = controller.openConflict(found.option());
                if (conflict.isPresent()) {
                    LOG.error("--{}={}: {}", LaunchOptions.Option.KB.argumentName(), knowledgeBase.get(), conflict.get());
                    showLaunchProblem("This datastore is open in another process",
                            found.option().name() + " — " + conflict.get()
                                    + ".\n\nEither close the other process, or open a different datastore.");
                    return false;
                }
                LOG.info("Opening {} with {} (--{})", found.option().uri(), found.controllerName(),
                        LaunchOptions.Option.KB.argumentName());
                SelectDataSourceController.prepareDataSource(controller, found.option());
                app.rootPane.getChildren().setAll(SelectDataSourceController.loadingProgressView());
                stage.setTitle("KOMET Startup");
                app.appMenu.setupMenus(app.rootPane);
                App.state.set(AppState.SELECTED_DATA_SOURCE);
                return true;
            }
        }
    }

    private static void showLaunchProblem(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Komet launch options");
        alert.setHeaderText(header);
        alert.setContentText(message + "\n\nChoose a knowledge base in the picker instead.");
        alert.showAndWait();
    }

    public void launchLandingPage(Stage stage, ConceptFacade loggedInUser) {
        try {
            app.rootPane.getChildren().clear(); // Clear the root pane before adding new content

            KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
            KometPreferences windowPreferences = appPreferences.node("main-komet-window");

            WindowSettings windowSettings = new WindowSettings(windowPreferences);

            FXMLLoader landingPageLoader = LandingPageViewFactory.createFXMLLoader();
            BorderPane landingPageBorderPane = landingPageLoader.load();

            String username = windowSettings.getView().calculator().getPreferredDescriptionTextWithFallbackOrNid(loggedInUser.nid());
            app.landingPageController = landingPageLoader.getController();

            // Set the logged-in user as author on the controller's single edit coordinate
            app.landingPageController.editCoordinate().authorForChangesProperty().setValue(loggedInUser);
            app.landingPageController.getWelcomeTitleLabel().setText("User: " + username);
            app.landingPageController.setSelectedDatasetTitle(
                    PrimitiveData.get().name() + NidLayoutMigration.titleSuffix());
            app.landingPageController.getGithubStatusHyperlink().setOnAction(_ -> app.appGithub.connectToGithub());

            stage.setTitle("Landing Page" + NidLayoutMigration.titleSuffix());
            stage.setMaximized(false);  // Change from true to false
            stage.setWidth(1035);       // Match the prefWidth from landing-page.fxml
            stage.setHeight(850);       // Match the prefHeight from landing-page.fxml
            // Where it was at the last quit on this knowledge base, if recorded (ike-issues#1151).
            if (!IS_BROWSER) {
                OpenWindowTracker.recordedLandingPage().ifPresent(saved -> ScreenFit.place(stage, saved));
            }
            stage.setOnCloseRequest(windowEvent -> {
                // This is called only when the user clicks the close button on the window
                App.state.set(SHUTDOWN);
                app.landingPageController.cleanup();
            });

            app.rootPane.getChildren().add(landingPageBorderPane);

            app.appMenu.setupMenus(landingPageBorderPane, stage);
        } catch (IOException e) {
            LOG.error("Failed to initialize the landing page window", e);
        }
    }


    /**
     * When a user selects the menu option View/New Journal a new Stage Window is launched.
     * This method will load a navigation panel to be a publisher and windows will be connected
     * (subscribed) to the activity stream.
     *
     * @param journalWindowSettings if present will give the size and positioning of the journal window
     */
    void launchJournalViewPage(PrefX journalWindowSettings, ConceptFacade loggedInUser) {
        Objects.requireNonNull(journalWindowSettings, "journalWindowSettings cannot be null");
        final KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        final KometPreferences windowPreferences = appPreferences.node(MAIN_KOMET_WINDOW);
        final WindowSettings windowSettings = new WindowSettings(windowPreferences);
        final ObservableViewNoOverride parentViewCoordinates = journalWindowSettings.getValue(PARENT_VIEW_COORDINATES);
        final UUID journalTopic = journalWindowSettings.getValue(JOURNAL_TOPIC);
        Objects.requireNonNull(journalTopic, "journalTopic cannot be null");

        // Set the author on the journal's actual working view (PARENT_VIEW_COORDINATES) — the instance the
        // journal and its inner editing windows are built from (handed to the view model below) — not on a
        // throwaway WindowSettings view that nothing reads. With the edit-coordinate listener registered
        // (IKE-Network/ike-issues#751) the landing author-set already rides the mirror to this instance; this
        // asserts it directly at journal launch as well.
        parentViewCoordinates.editCoordinate().authorForChangesProperty().setValue(loggedInUser);

        Config journalConfig = new Config(KViewResources.journalFxml())
                .updateViewModel("journalViewModel", journalViewModel -> {
                    journalViewModel.setPropertyValue(CURRENT_JOURNAL_WINDOW_TOPIC, journalTopic);
                    journalViewModel.setPropertyValue(WINDOW_SETTINGS, windowSettings);
                    journalViewModel.setPropertyValue(JournalViewModel.PARENT_VIEW_COORDINATES, parentViewCoordinates);
                });

        JFXNode<BorderPane, JournalController> journalJFXNode = FXMLMvvmLoader.make(journalConfig);
        BorderPane journalBorderPane = journalJFXNode.node();
        JournalController journalController = journalJFXNode.controller();

        journalController.setup(windowPreferences);

        Scene sourceScene = new Scene(journalBorderPane, DEFAULT_JOURNAL_WIDTH, DEFAULT_JOURNAL_HEIGHT);
        addStylesheets(sourceScene, KOMET_CSS, KVIEW_CSS);

        Stage journalStage = new Stage();
        journalStage.getIcons().setAll(app.appIcon);
        journalStage.setScene(sourceScene);

        // The window title follows the journal's live name, so a rename shows at once (ike-issues#1128).
        // It must be in place before registering with WindowMenuManager, which sorts stages by title.
        journalStage.titleProperty().bind(JournalNames.get().nameProperty(journalTopic));

        app.appMenu.generateMsWindowsMenu(journalBorderPane, journalStage);

        // Get the UUID-based directory name from preferences
        String journalDirName = journalWindowSettings.getValue(JOURNAL_DIR_NAME);

        // For new journals (no UUID yet), generate one using the controller's UUID
        if (journalDirName == null) {
            journalDirName = journalController.getJournalDirName();
            journalWindowSettings.setValue(JOURNAL_DIR_NAME, journalDirName);
        }

        if (journalWindowSettings.getValue(JOURNAL_HEIGHT) != null) {
            // Moved onto a connected screen if it was saved on one that is gone (ike-issues#1151).
            ScreenFit.place(journalStage, new Rectangle2D(
                    journalWindowSettings.getValue(JOURNAL_XPOS), journalWindowSettings.getValue(JOURNAL_YPOS),
                    journalWindowSettings.getValue(JOURNAL_WIDTH), journalWindowSettings.getValue(JOURNAL_HEIGHT)));
        } else {
            journalStage.setMaximized(true);
        }

        // Reopened at the next launch if it is open at quit (ike-issues#1151).
        if (!IS_BROWSER) {
            app.openWindowTracker.track(journalStage, () -> Optional.of(OpenWindows.Entry.journal(journalTopic)));
        }

        journalStage.setOnHidden(windowEvent -> {
            // This handler serves the genuine close-one-journal case. During app quit,
            // App.quit() has already saved every journal window and then stopped the data
            // services this save path needs (PrimitiveData AND Preferences) — no window-state
            // save may run after data services stop (ike-issues#944).
            if (App.shutdownInProgress) {
                return;
            }
            app.saveJournalWindowsToPreferences();
            journalController.shutdown();
            app.journalControllersList.remove(journalController);

            journalWindowSettings.setValue(CAN_DELETE, true);
            app.kViewEventBus.publish(JOURNAL_TOPIC,
                    new JournalTileEvent(this, UPDATE_JOURNAL_TILE, journalWindowSettings));
        });

        journalStage.setOnShown(windowEvent -> {
            journalController.restoreWindows(windowSettings, journalWindowSettings);

            KometNodeFactory navigatorNodeFactory = new GraphNavigatorNodeFactory();
            KometNodeFactory searchNodeFactory = new SearchNodeFactory();

            journalController.launchKometFactoryNodes(
                    navigatorNodeFactory,
                    searchNodeFactory);
            // load additional panels
            journalController.loadNextGenReasonerPanel();
            journalController.loadNextGenSearchPanel();
        });
        // disable the delete menu option for a Journal Card.
        journalWindowSettings.setValue(CAN_DELETE, false);
        app.kViewEventBus.publish(JOURNAL_TOPIC, new JournalTileEvent(this, UPDATE_JOURNAL_TILE, journalWindowSettings));
        app.journalControllersList.add(journalController);

        if (IS_BROWSER) {
            app.webAPI.openStageAsTab(journalStage, journalDirName);
        } else {
            journalStage.show();
        }
    }

    /**
     * Launchs a new KE Editor Window
     *
     * @param klWindowSettings if present will give the size and positioning of the journal window
     * @param standardWindow whether the window to load is a standard (application-provided) window
     *                       from the standard-windows folder rather than a user-created one
     */
    void launchKLEditorViewPage(PrefX klWindowSettings, ConceptFacade loggedInUser, String windowToLoad,
                                boolean standardWindow) {
        launchKLEditorViewPage(klWindowSettings, loggedInUser, windowToLoad, standardWindow, Optional.empty());
    }

    /**
     * Opens a KL editor window on a layout.
     *
     * @param klWindowSettings the window settings
     * @param loggedInUser     the author for changes
     * @param windowToLoad     the layout's title, or null for a new layout
     * @param standardWindow   whether the layout is a standard window
     * @param bounds           where to open it, when reopening a window open at the last quit
     *                         (ike-issues#1151); empty opens it maximized
     */
    void launchKLEditorViewPage(PrefX klWindowSettings, ConceptFacade loggedInUser, String windowToLoad,
                                boolean standardWindow, Optional<Rectangle2D> bounds) {
        Objects.requireNonNull(klWindowSettings, "klWindowSettings cannot be null");

        final KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        final KometPreferences klEditorAppPreferences = appPreferences.node(KL_EDITOR_APP);
        final KometPreferences windowsPreferences = klEditorAppPreferences.node(
                standardWindow ? KL_STANDARD_WINDOWS_DIR : KL_USER_WINDOWS_DIR);
        final WindowSettings windowSettings = new WindowSettings(klEditorAppPreferences);

        ObservableEditCoordinate editCoordinate = windowSettings.getView().editCoordinate();
        editCoordinate.authorForChangesProperty().setValue(loggedInUser);

        FXMLLoader loader = new FXMLLoader(KLEditorMainScreenController.class.getResource("KLEditorMainScreen.fxml"));
        Parent root = null;
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        KLEditorMainScreenController klEditorMainScreenController = loader.getController();

        klEditorMainScreenController.init(windowsPreferences, windowSettings, windowToLoad, standardWindow);

        Scene sourceScene = new Scene(root, DEFAULT_JOURNAL_WIDTH, DEFAULT_JOURNAL_HEIGHT);
        addStylesheets(sourceScene, KLEDITOR_CSS, KLCORE_CSS, KLEDITOR_WINDOW_CSS, ICONS);

        Stage klEditorWindowStage = new Stage();
        klEditorWindowStage.getIcons().setAll(app.appIcon);
        klEditorWindowStage.setScene(sourceScene);
        // Set the title before generateKLEditorMenu(...) registers the stage with
        // WindowMenuManager, so this window is labelled correctly in every window's Window menu
        // (mirrors the journal window's setTitle-then-menu ordering).
        klEditorWindowStage.setTitle("Knowledge Layout Editor");

        app.appMenu.generateKLEditorMenu((BorderPane) root, klEditorWindowStage, klEditorMainScreenController);

        bounds.ifPresentOrElse(saved -> ScreenFit.place(klEditorWindowStage, saved),
                () -> klEditorWindowStage.setMaximized(true));

        klEditorWindowStage.setOnHidden(windowEvent -> klEditorMainScreenController.shutdown());

        // Reopened at the next launch if it is open at quit on a saved layout (ike-issues#1151).
        if (!IS_BROWSER) {
            app.openWindowTracker.track(klEditorWindowStage, () -> klEditorMainScreenController.savedWindowTitle()
                    .map(title -> OpenWindows.Entry.klEditor(title, klEditorMainScreenController.isStandardWindows(),
                            new Rectangle2D(klEditorWindowStage.getX(), klEditorWindowStage.getY(),
                                    klEditorWindowStage.getWidth(), klEditorWindowStage.getHeight()))));
        }

        if (IS_BROWSER) {
            app.webAPI.openStageAsTab(klEditorWindowStage, "KL Editor");
        } else {
            klEditorWindowStage.show();
        }
    }
}
