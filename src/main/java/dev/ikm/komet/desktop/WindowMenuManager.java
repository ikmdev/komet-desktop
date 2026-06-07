package dev.ikm.komet.desktop;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks application windows and keeps each window's "Window" menu synchronized with
 * the list of open stages. Automatically removes stages when their windows close.
 * <p>Usage: call {@link #addStage(Stage, MenuBar)} after creating each tracked window's menu bar.
 * The Window menu is inserted before "Help" (or appended if no Help menu exists).
 */
public class WindowMenuManager implements ListChangeListener<Window> {

    private static final Logger LOG = LoggerFactory.getLogger(WindowMenuManager.class);
    /** Key under which each window CheckMenuItem stores its focus Subscription for later release. */
    private static final String FOCUS_SUBSCRIPTION = "windowMenu.focusSubscription";
    private static final WindowMenuManager INSTANCE = new WindowMenuManager();

    private final ConcurrentHashMap<Stage, MenuBar> managedMenus = new ConcurrentHashMap<>();

    private WindowMenuManager() {
        Window.getWindows().addListener(this);
    }

    public static WindowMenuManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a stage and its menu bar. Triggers a rebuild of Window menus across all
     * registered stages to include the newly added stage.
     */
    public static void addStage(Stage stage, MenuBar menuBar) {
        INSTANCE.managedMenus.put(stage, menuBar);
        updateMenus();
    }

    /**
     * Removes closed stages from the registry and rebuilds Window menus.
     */
    @Override
    public void onChanged(Change<? extends Window> c) {
        boolean changed = false;
        while (c.next()) {
            for (Window removed : c.getRemoved()) {
                if (removed instanceof Stage stage && INSTANCE.managedMenus.containsKey(stage)) {
                    LOG.info("Stage removed: {}", stage.getTitle());
                    INSTANCE.managedMenus.remove(stage);
                    changed = true;
                }
            }
        }
        if (changed) {
            updateMenus();
        }
    }

    /**
     * Rebuilds the "Window" menu in every registered menu bar. If a menu bar has no
     * "Window" menu, one is inserted before "Help" (or appended if no Help menu exists).
     */
    static void updateMenus() {
        INSTANCE.managedMenus.forEach((stage, menuBar) ->
                menuBar.getMenus().stream()
                        .filter(m -> "Window".equals(m.getText()))
                        .findFirst()
                        .ifPresentOrElse(
                                windowMenu -> addWindowMenuItems(stage, windowMenu),
                                () -> Platform.runLater(() -> {
                                    Menu windowMenu = new Menu("Window");
                                    int insertAt = menuBar.getMenus().size();
                                    for (int i = 0; i < menuBar.getMenus().size(); i++) {
                                        if ("Help".equals(menuBar.getMenus().get(i).getText())) {
                                            insertAt = i;
                                            break;
                                        }
                                    }
                                    menuBar.getMenus().add(insertAt, windowMenu);
                                    addWindowMenuItems(stage, windowMenu);
                                })
                        )
        );
    }

    private static void addWindowMenuItems(Stage windowStage, Menu windowMenu) {
        // Release the previous build's title bindings and focus subscriptions before discarding
        // the old items, so listeners do not accumulate on each stage's properties across rebuilds.
        windowMenu.getItems().forEach(item -> {
            if (item instanceof CheckMenuItem checkItem) {
                checkItem.textProperty().unbind();
            }
            if (item.getProperties().get(FOCUS_SUBSCRIPTION) instanceof Subscription subscription) {
                subscription.unsubscribe();
            }
        });
        windowMenu.getItems().clear();

        MenuItem minimize = new MenuItem("Minimize");
        minimize.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        minimize.setOnAction(e -> windowStage.setIconified(true));
        minimize.setDisable(App.IS_BROWSER);
        windowMenu.getItems().add(minimize);

        MenuItem cycle = new MenuItem("Cycle through windows");
        cycle.setAccelerator(new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.SHORTCUT_DOWN));
        cycle.setOnAction(e -> {
            List<Stage> sorted = INSTANCE.managedMenus.keySet().stream()
                    .sorted(Comparator.comparing(Stage::getTitle,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
            int idx = sorted.indexOf(windowStage);
            sorted.get((idx + 1) % sorted.size()).toFront();
        });
        cycle.setDisable(App.IS_BROWSER);
        windowMenu.getItems().add(cycle);

        MenuItem bringAll = new MenuItem("Bring all to front");
        bringAll.setOnAction(e -> INSTANCE.managedMenus.keySet().forEach(Stage::toFront));
        bringAll.setDisable(App.IS_BROWSER);
        windowMenu.getItems().add(bringAll);

        windowMenu.getItems().add(new SeparatorMenuItem());

        // One CheckMenuItem per open stage, checked when that stage is focused
        INSTANCE.managedMenus.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getTitle(),
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(entry -> {
                    Stage s = entry.getKey();
                    CheckMenuItem item = new CheckMenuItem();
                    // Bind the label to the live stage title so the entry is correct even if the
                    // title is set after the stage is registered, and tracks later title changes.
                    item.textProperty().bind(s.titleProperty());
                    // subscribe(...) sets the initial checked state and tracks focus; the returned
                    // Subscription is released on the next rebuild (see the top of this method).
                    item.getProperties().put(FOCUS_SUBSCRIPTION,
                            s.focusedProperty().subscribe(item::setSelected));
                    item.setOnAction(e -> s.toFront());
                    windowMenu.getItems().add(item);
                });
    }
}
