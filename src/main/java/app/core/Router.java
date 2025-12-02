package app.core;

import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public final class Router {
    private static Router INSTANCE;

    private final BorderPane root;
    private final Deque<View> history = new ArrayDeque<>();
    private View current = null;
    private Consumer<String> onTitle;

    private Router(BorderPane root) { this.root = root; }

    public static void init(BorderPane root) { INSTANCE = new Router(root); }
    public static Router get() {
        if (INSTANCE == null) throw new IllegalStateException("Router not initialized");
        return INSTANCE;
    }

    /** Позволяет менять заголовок в шапке при навигации. */
    public void setOnTitle(Consumer<String> cb) { this.onTitle = cb; }

    /** Обычный переход с пушем в историю. */
    public void go(View view) { navigate(view, true); }

    /** На главный экран без добавления в историю. */
    public void home() {
        history.clear();
        navigate(View.HOME, false);
    }

    /** Шаг назад по стеку. Если пусто — уходим на главную. */
    public void back() {
        if (!history.isEmpty()) {
            View prev = history.pop();
            navigate(prev, false);
        } else {
            home();
        }
    }

    public boolean canGoBack() { return !history.isEmpty(); }

    private void navigate(View view, boolean pushToHistory) {
        if (pushToHistory && current != null) {
            history.push(current);
        }
        Parent content = Fxml.load(view.fxml());
        root.setCenter(content);
        current = view;
        if (onTitle != null) onTitle.accept(view.title());
    }
}
