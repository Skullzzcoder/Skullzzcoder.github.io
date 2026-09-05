package fake;

import java.util.ArrayList;
import java.util.List;

/** Shaped like the real one: a static Event field per callback, nested interfaces. */
public final class ClientReceiveMessageEvents {

    public interface Game {
        void onGameMessage(String message, boolean overlay);
    }

    public interface Chat {
        void onChatMessage(String message);
    }

    public static final List<Object> listeners = new ArrayList<>();

    public static final Event<Game> GAME = new Event<Game>() {
        @Override public void register(Game listener) { listeners.add(listener); }
    };

    public static final Event<Chat> CHAT = new Event<Chat>() {
        @Override public void register(Chat listener) { listeners.add(listener); }
    };

    /** A field whose type is not generic at all, to prove the fallback works. */
    public static final Event RAW = new Event() {
        @Override public void register(Object listener) { listeners.add(listener); }
    };

    private ClientReceiveMessageEvents() {
    }
}
