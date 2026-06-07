package dev.ikm.komet.desktop;

import com.sun.management.OperatingSystemMXBean;
import dev.ikm.komet.desktop.aboutdialog.AboutDialog;
import dev.ikm.komet.desktop.maintenance.ChangeSetSummaryWindow;
import dev.ikm.komet.desktop.maintenance.DuplicateSemanticWithdrawerDialog;
import dev.ikm.komet.desktop.maintenance.LuceneIndexAnalysisWindow;
import dev.ikm.tinkar.provider.search.Indexer;
import dev.ikm.tinkar.provider.search.RecreateIndex;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.komet.framework.preferences.KometPreferencesStage;
import dev.ikm.komet.framework.window.WindowSettings;
import dev.ikm.komet.kleditorapp.view.KLEditorMainScreenController;
import dev.ikm.komet.kview.mvvm.view.changeset.ExportController;
import dev.ikm.komet.kview.mvvm.view.changeset.ImportController;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.carlfx.cognitive.loader.Config;
import org.carlfx.cognitive.loader.FXMLMvvmLoader;
import org.carlfx.cognitive.loader.JFXNode;
//import org.scenicview.ScenicView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Optional;
import java.util.prefs.BackingStoreException;

import static dev.ikm.komet.desktop.App.*;
import static dev.ikm.komet.desktop.AppState.RUNNING;
import static dev.ikm.komet.kview.fxutils.FXUtils.getFocusedWindow;
import static dev.ikm.komet.kview.mvvm.view.changeset.exchange.GitTask.OperationMode.PULL;
import static dev.ikm.komet.kview.mvvm.view.changeset.exchange.GitTask.OperationMode.SYNC;
import static dev.ikm.komet.kview.mvvm.viewmodel.ViewModelKey.VIEW_PROPERTIES;
import static dev.ikm.komet.preferences.JournalWindowPreferences.MAIN_KOMET_WINDOW;

public class AppMenu {

    private static final Logger LOG = LoggerFactory.getLogger(AppMenu.class);

    /** IKE getting-started page, opened from Help &gt; Getting started in the OS default browser. */
    private static final String GETTING_STARTED_URL = "https://ike.network";

    private final App app;
    KometPreferencesStage kometPreferencesStage;

    private Stage overlayStage;
    private Timeline resourceUsageTimeline;

    public AppMenu(App app) {
        this.app = app;
    }

    /**
     * create the menu for windows used on a journal window (ie no vbox at the top of a border pane)
     * @param borderPane border pane for the journal
     * @param stage stage for the journal window
     */
    void generateMsWindowsMenu(BorderPane borderPane, Stage stage) {
        this.generateMsWindowsMenu(borderPane, stage, null);
    }

    /**
     * create the menu for windows for classic komet
     * @param kometRoot border pane for classic komet
     * @param stage stage for classic komet
     * @param topBarVBox contains the top of the border pane with the parent coordinate menu as well as the
     *                   menu we will add to it
     */
    void generateMsWindowsMenu(BorderPane kometRoot, Stage stage, VBox topBarVBox) {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem about = new MenuItem("About");
        about.setOnAction(_ -> showAboutDialog());
        fileMenu.getItems().add(about);
        MenuItem importMenuItem = new MenuItem("Import Dataset");
        importMenuItem.setOnAction(actionEvent -> openImport(stage));
        fileMenu.getItems().add(importMenuItem);
        MenuItem exportDatasetMenuItem = new MenuItem("Export Dataset");
        exportDatasetMenuItem.setOnAction(actionEvent -> openExport(stage));
        fileMenu.getItems().add(exportDatasetMenuItem);
        MenuItem menuItemQuit = new MenuItem("Quit");
        menuItemQuit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN));
        menuItemQuit.setOnAction(actionEvent -> app.quit());
        fileMenu.getItems().add(menuItemQuit);

        Menu editMenu = new Menu("Edit");
        MenuItem landingPage = new MenuItem("Landing Page");
        landingPage.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN));
        landingPage.setOnAction(actionEvent -> app.appPages.launchLandingPage(App.primaryStage, userProperty.get()));
        landingPage.setDisable(IS_BROWSER);
        editMenu.getItems().add(landingPage);

        Menu helpMenu = new Menu("Help");
        helpMenu.getItems().add(new MenuItem("Getting started"));

        menuBar.getMenus().addAll(fileMenu, editMenu, helpMenu);
        menuBar.setUseSystemMenuBar(true);

        // Register with WindowMenuManager — inserts a "Window" menu before "Help"
        WindowMenuManager.addStage(stage, menuBar);

        if (topBarVBox != null) {
            // add menu to the classic komet top bar
            Platform.runLater(() -> topBarVBox.getChildren().addFirst(menuBar));
        } else {
            // add menu to the journal window
            Platform.runLater(() -> kometRoot.setTop(menuBar));
        }
    }

    /**
     * Builds and attaches the Knowledge Layout Editor's window menu bar, then registers the
     * stage with {@link WindowMenuManager} so that a live "Window" menu (listing all open
     * windows) is inserted before "Help" and kept up to date. Uses the same mechanism as the
     * journal and landing windows ({@code setUseSystemMenuBar} plus stage tracking) rather than
     * a bespoke per-window bar.
     *
     * @param root       the editor window's root border pane the menu bar is attached to
     * @param stage      the editor window's stage, tracked for the Window menu
     * @param controller the editor controller, used to trigger Save Layout and reflect its enabled state
     */
    void generateKLEditorMenu(BorderPane root, Stage stage, KLEditorMainScreenController controller) {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem saveLayout = new MenuItem("Save Layout");
        saveLayout.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveLayout.setOnAction(actionEvent -> controller.saveWindow());
        saveLayout.disableProperty().bind(controller.titleIsEmpty());
        MenuItem about = new MenuItem("About");
        about.setOnAction(actionEvent -> showAboutDialog());
        MenuItem closeWindow = new MenuItem("Close");
        closeWindow.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeWindow.setOnAction(actionEvent -> stage.close());
        fileMenu.getItems().addAll(saveLayout, new SeparatorMenuItem(), about, new SeparatorMenuItem(), closeWindow);

        Menu editMenu = new Menu("Edit");
        MenuItem landingPage = new MenuItem("Landing Page");
        landingPage.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN));
        landingPage.setOnAction(actionEvent -> app.appPages.launchLandingPage(App.primaryStage, userProperty.get()));
        landingPage.setDisable(IS_BROWSER);
        editMenu.getItems().add(landingPage);

        Menu helpMenu = createHelpMenu();

        menuBar.getMenus().addAll(fileMenu, editMenu, helpMenu);
        menuBar.setUseSystemMenuBar(true);

        // Register with WindowMenuManager — inserts a live "Window" menu before "Help"
        WindowMenuManager.addStage(stage, menuBar);

        Platform.runLater(() -> root.setTop(menuBar));
    }

    Menu createExchangeMenu() {
        Menu exchangeMenu = new Menu("Exchange");

        MenuItem infoMenuItem = new MenuItem("Info");
        infoMenuItem.setOnAction(actionEvent -> app.appGithub.infoAction());
        MenuItem pullMenuItem = new MenuItem("Pull");
        pullMenuItem.setOnAction(actionEvent -> app.appGithub.executeGitTask(PULL));
        MenuItem pushMenuItem = new MenuItem("Sync");
        pushMenuItem.setOnAction(actionEvent -> app.appGithub.executeGitTask(SYNC));

        exchangeMenu.getItems().addAll(infoMenuItem, pullMenuItem, pushMenuItem);
        return exchangeMenu;
    }

    /*
    // This can be used to add a developer menu with Scenic View
    // This is very useful for debugging JavaFX applications.
    // Therefore this comment shouldn't be deleted
    Menu createDevMenu(Parent node) {
        Menu devMenu = new Menu("Dev");

        MenuItem reloadMenuItem = new MenuItem("Scenic View");
        reloadMenuItem.setOnAction(actionEvent -> {
            var stage2 = new Stage();
            if(WebAPI.isBrowser()) {
                WebAPI.getWebAPI(node.getScene()).openStageAsPopup(stage2);
            }
            ScenicView.show(node, stage2);
        });
        // Add shortcut Ctrl+Shift+S to open Scenic View
        KeyCombination scenicViewKeyCombo = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
        reloadMenuItem.setAccelerator(scenicViewKeyCombo);
        devMenu.getItems().add(reloadMenuItem);
        return devMenu;
    }*/

    public void showAboutDialog() {
        AboutDialog aboutDialog = new AboutDialog();
        aboutDialog.showAndWait();
    }

    /**
     * Builds and attaches a menu bar to the given parent without stage tracking.
     * Used for transient pages (login, data source selection) that don't need a Window menu.
     */
    void setupMenus(Parent parent) {
        MenuBar menuBar = buildMenuBar();
        menuBar.setUseSystemMenuBar(true);
        if (parent instanceof BorderPane borderPane) {
            borderPane.setTop(menuBar);
        } else if (parent instanceof Pane pane) {
            pane.getChildren().addFirst(menuBar);
        }
    }

    /**
     * Builds and attaches a menu bar to the given border pane, then registers the stage with
     * {@link WindowMenuManager} so that a "Window" menu (listing all open windows) is
     * automatically inserted before "Help" and kept up to date.
     */
    void setupMenus(BorderPane borderPane, Stage stage) {
        MenuBar menuBar = buildMenuBar();
        menuBar.setUseSystemMenuBar(true);
        borderPane.setTop(menuBar);
        WindowMenuManager.addStage(stage, menuBar);
    }

    private MenuBar buildMenuBar() {
        Menu kometAppMenu = new Menu("Komet");
        MenuItem prefsItem = new MenuItem("Komet preferences...");
        prefsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.META_DOWN));
        prefsItem.setOnAction(event -> kometPreferencesStage.showPreferences());
        MenuItem quitItem = new MenuItem("Quit");
        quitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.META_DOWN));
        quitItem.setOnAction(event -> app.quit());
        kometAppMenu.getItems().addAll(prefsItem, new SeparatorMenuItem(), quitItem);

        MenuBar menuBar = new MenuBar(kometAppMenu);
        if (App.state.get() == RUNNING) {
            menuBar.getMenus().addAll(createFileMenu(), createEditMenu(), createViewMenu(), createToolsMenu());
        }
        menuBar.getMenus().add(createExchangeMenu());
        menuBar.getMenus().add(createHelpMenu());
        return menuBar;
    }

    private Menu createToolsMenu() {
        Menu toolsMenu = new Menu("Tools");
        MenuItem withdrawDuplicatesItem = new MenuItem("Validate Single-Semantic Patterns…");
        withdrawDuplicatesItem.setOnAction(actionEvent -> openDuplicateSemanticWithdrawer());
        MenuItem summarizeChangeSetItem = new MenuItem("Summarize Change Set…");
        summarizeChangeSetItem.setOnAction(actionEvent -> openChangeSetSummary());
        MenuItem analyzeLuceneItem = new MenuItem("Analyze Lucene Index…");
        analyzeLuceneItem.setOnAction(actionEvent -> openLuceneIndexAnalysis());
        toolsMenu.getItems().addAll(withdrawDuplicatesItem, summarizeChangeSetItem, analyzeLuceneItem);
        return toolsMenu;
    }

    private void openLuceneIndexAnalysis() {
        // The analyzer operates on the live Indexer.indexWriter() — the running
        // search provider's writer. We pass the data store root so the report
        // can show the full lucene/ path, plus a rebuild callback that the
        // user can invoke via the "Wipe & Rebuild" choice in the confirmation
        // dialog. The window itself decides which option to pass to the analyzer.
        Optional<File> dataStoreRoot = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        Stage owner = (Stage) getFocusedWindow();
        LuceneIndexAnalysisWindow.openWithConfirmation(
                dataStoreRoot.orElse(null), owner, AppMenu::rebuildLuceneFromEntityStore);
    }

    /**
     * Default Lucene rebuild callback for the desktop app: wraps the active
     * static {@link Indexer} state and runs {@link RecreateIndex}, which walks
     * every entity in parallel and indexes via the existing per-entity logic.
     *
     * <p>{@code Indexer.wrapActiveState()} returns a transient handle pointing
     * at the same singleton state the search provider initialized at startup —
     * we are not opening a second writer or replacing the static fields. The
     * writer the analyzer wiped is the same writer this rebuild populates.
     */
    private static void rebuildLuceneFromEntityStore(
            org.apache.lucene.index.IndexWriter writer,
            dev.ikm.tinkar.provider.search.maintenance.LuceneIndexAnalyzer.ProgressCallback progress) throws Exception {
        progress.update("Walking entity store for parallel reindex", -1);
        Indexer activeHandle = Indexer.wrapActiveState();
        RecreateIndex task = new RecreateIndex(activeHandle);
        // TrackingCallable implements Callable; call() runs the task
        // synchronously on this thread (we're already on a background
        // analyzer thread, not the JavaFX UI thread).
        task.call();
    }

    private void openDuplicateSemanticWithdrawer() {
        KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        KometPreferences windowPreferences = appPreferences.node(MAIN_KOMET_WINDOW);
        WindowSettings windowSettings = new WindowSettings(windowPreferences);
        DuplicateSemanticWithdrawerDialog dialog =
                new DuplicateSemanticWithdrawerDialog(windowSettings.getView());
        dialog.show();
    }

    private void openChangeSetSummary() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Change Set");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Change Set ZIP", "*.zip"));
        // Default to the directory the change-set writer uses, so the most recent
        // exports are one open away. Falls through to FileChooser's default
        // (last-used directory) when the data store root isn't set or the
        // changeSets path doesn't exist yet.
        Optional<File> dataStoreRoot = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        dataStoreRoot.ifPresent(root -> {
            File changeSetsDir = root.toPath().resolve("changeSets")
                    .resolve("src").resolve("main").resolve("resources").toFile();
            if (changeSetsDir.isDirectory()) {
                chooser.setInitialDirectory(changeSetsDir);
            }
        });
        Stage owner = (Stage) getFocusedWindow();
        File selected = chooser.showOpenDialog(owner);
        if (selected == null) return;
        ChangeSetSummaryWindow.openFor(selected, owner);
    }

    private Menu createFileMenu() {
        Menu fileMenu = new Menu("File");

        // Import Dataset Menu Item
        MenuItem importDatasetMenuItem = new MenuItem("Import Dataset...");
        importDatasetMenuItem.setOnAction(actionEvent -> openImport(App.primaryStage));

        // Export Dataset Menu Item
        MenuItem exportDatasetMenuItem = new MenuItem("Export Dataset...");
        exportDatasetMenuItem.setOnAction(actionEvent -> openExport(App.primaryStage));

        // Add menu items to the File menu
        fileMenu.getItems().addAll(importDatasetMenuItem, exportDatasetMenuItem);

        return fileMenu;
    }

    private Menu createEditMenu() {
        Menu editMenu = new Menu("Edit");
        editMenu.getItems().addAll(
                createMenuItem("Undo"),
                createMenuItem("Redo"),
                new SeparatorMenuItem(),
                createMenuItem("Cut"),
                createMenuItem("Copy"),
                createMenuItem("Paste"),
                createMenuItem("Select All"));
        return editMenu;
    }

    private Menu createViewMenu() {
        Menu viewMenu = new Menu("View");
        MenuItem classicKometMenuItem = new MenuItem("Classic Komet");
        classicKometMenuItem.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN));
        classicKometMenuItem.setOnAction(actionEvent -> {
            try {
                app.appClassicKomet.launchClassicKomet();
            } catch (IOException | BackingStoreException e) {
                throw new RuntimeException(e);
            }
        });
        viewMenu.getItems().addAll(classicKometMenuItem, createResourceUsageItem());
        return viewMenu;
    }

    private Menu createHelpMenu() {
        Menu helpMenu = new Menu("Help");
        MenuItem gettingStarted = new MenuItem("Getting started");
        // Open the IKE getting-started page in the OS default web browser (works on desktop and
        // under JPro). HostServices delegates to the operating system's registered URL handler.
        gettingStarted.setOnAction(event -> app.getHostServices().showDocument(GETTING_STARTED_URL));
        helpMenu.getItems().add(gettingStarted);
        return helpMenu;
    }


    private MenuItem createMenuItem(String title) {
        MenuItem menuItem = new MenuItem(title);
        menuItem.setOnAction(this::handleEvent);
        return menuItem;
    }


    private void handleEvent(ActionEvent actionEvent) {
        LOG.debug("clicked " + actionEvent.getSource());  // NOSONAR
    }



    public void openImport(Stage owner) {
        KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        KometPreferences windowPreferences = appPreferences.node(MAIN_KOMET_WINDOW);
        WindowSettings windowSettings = new WindowSettings(windowPreferences);
        Stage importStage = new Stage(StageStyle.TRANSPARENT);
        importStage.initOwner(owner);
        //set up ImportViewModel
        Config importConfig = new Config(ImportController.class.getResource("import.fxml"))
                .updateViewModel("importViewModel", (importViewModel) ->
                        importViewModel.setPropertyValue(VIEW_PROPERTIES,
                                windowSettings.getView().makeOverridableViewProperties("AppMenu.openImport")));
        JFXNode<Pane, ImportController> importJFXNode = FXMLMvvmLoader.make(importConfig);

        Pane importPane = importJFXNode.node();
        Scene importScene = new Scene(importPane, Color.TRANSPARENT);
        importStage.setScene(importScene);
        importStage.show();
    }

    public void openExport(Stage owner) {
        KometPreferences appPreferences = KometPreferencesImpl.getConfigurationRootPreferences();
        KometPreferences windowPreferences = appPreferences.node(MAIN_KOMET_WINDOW);
        WindowSettings windowSettings = new WindowSettings(windowPreferences);
        Stage exportStage = new Stage(StageStyle.TRANSPARENT);
        exportStage.initOwner(owner);
        //set up ExportViewModel
        Config exportConfig = new Config(ExportController.class.getResource("export.fxml"))
                .updateViewModel("exportViewModel", (exportViewModel) ->
                        exportViewModel.setPropertyValue(VIEW_PROPERTIES,
                                windowSettings.getView().makeOverridableViewProperties("AppMenu.openExport")));
        JFXNode<Pane, ExportController> exportJFXNode = FXMLMvvmLoader.make(exportConfig);

        Pane exportPane = exportJFXNode.node();
        Scene exportScene = new Scene(exportPane, Color.TRANSPARENT);
        exportStage.setScene(exportScene);
        exportStage.show();
    }


    /**
     * Create a Resource Usage overlay to display metrics
     *
     * @return The menu item for launching the resource usage overlay.
     */
    private MenuItem createResourceUsageItem() {
        MenuItem resourceUsageItem = new MenuItem("Resource Usage");
        KeyCombination classicKometKeyCombo = new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN);
        resourceUsageItem.setOnAction(actionEvent -> {
            if (overlayStage != null && overlayStage.isShowing()) {
                overlayStage.hide();
            } else {
                showResourceUsageOverlay();
            }
        });
        resourceUsageItem.setAccelerator(classicKometKeyCombo);
        return resourceUsageItem;
    }

    /**
     * Show the resource usage overlay.
     */
    private void showResourceUsageOverlay() {
        overlayStage = new Stage();
        overlayStage.initOwner(getFocusedWindow());
        overlayStage.initModality(Modality.APPLICATION_MODAL);
        overlayStage.initStyle(StageStyle.TRANSPARENT);

        VBox overlayContent = new VBox();
        overlayContent.setAlignment(Pos.CENTER);
        overlayContent.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5); -fx-padding: 20;");

        Label cpuUsageLabel = new Label();
        Label memoryUsageLabel = new Label();
        cpuUsageLabel.setTextFill(Color.WHITE);
        memoryUsageLabel.setTextFill(Color.WHITE);
        overlayContent.getChildren().addAll(cpuUsageLabel, memoryUsageLabel);

        Scene overlayScene = new Scene(overlayContent, 300, 200, Color.TRANSPARENT);
        overlayStage.setScene(overlayScene);

        // Set custom close request handler
        overlayStage.setOnCloseRequest(event -> {
            if (resourceUsageTimeline != null) {
                resourceUsageTimeline.stop(); // Stop the timeline
            }
            overlayStage.hide(); // Hide the stage
        });
        overlayStage.show();

        // Create and start the timeline to update resource usage
        resourceUsageTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            updateResourceUsage(cpuUsageLabel, memoryUsageLabel);
        }));
        resourceUsageTimeline.setCycleCount(Timeline.INDEFINITE);
        resourceUsageTimeline.play();
    }

    /**
     * Update the resource usage labels with CPU and memory usage.
     *
     * @param cpuUsageLabel the label to be used for the CPU usage
     * @param memoryUsageLabel the label to be used for the memory usage
     */
    private void updateResourceUsage(final Label cpuUsageLabel, final Label memoryUsageLabel) {
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        double cpuLoad = -1;
        if (osBean instanceof OperatingSystemMXBean sunOsBean) {
            cpuLoad = sunOsBean.getCpuLoad() * 100;

            long totalMemory = Runtime.getRuntime().totalMemory();
            long freeMemory = Runtime.getRuntime().freeMemory();
            long usedMemory = totalMemory - freeMemory;

            cpuUsageLabel.setText("CPU Usage: %.2f%%".formatted(cpuLoad));
            memoryUsageLabel.setText("Memory Usage: %,d MB / %,d MB".formatted(
                    usedMemory / (1024 * 1024), totalMemory / (1024 * 1024)));
        }
    }
}
