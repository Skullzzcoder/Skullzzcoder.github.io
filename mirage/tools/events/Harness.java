import dev.skullzz.mirage.client.Events;
import java.util.ArrayList;
import java.util.List;

public class Harness {
    static int fails = 0;
    static void check(String what, boolean ok) {
        if (!ok) { System.out.println("FAIL " + what); fails++; }
    }

    public static void main(String[] argv) throws Exception {
        List<String> seen = new ArrayList<>();

        // The case that was broken in game: a generic Event field. register(T) erases to
        // register(Object), so asking the method what it wants gives Object.
        Events.Result game = Events.subscribe("fake.ClientReceiveMessageEvents", "GAME",
                (proxy, method, args) -> {
                    for (Object argument : args) {
                        if (argument instanceof String text) seen.add(text);
                    }
                    return null;
                });
        check("a generic event subscribes: " + game.reason, game.ok);

        // And it actually receives.
        for (Object listener : fake.ClientReceiveMessageEvents.listeners) {
            if (listener instanceof fake.ClientReceiveMessageEvents.Game handler) {
                handler.onGameMessage("Bob paid you $10", false);
            }
        }
        check("the listener is called", seen.contains("Bob paid you $10"));

        Events.Result chat = Events.subscribe("fake.ClientReceiveMessageEvents", "CHAT",
                (proxy, method, args) -> null);
        check("a second field subscribes: " + chat.reason, chat.ok);

        // A raw field has no generic type to read; the nested-interface fallback has to
        // find it, or nothing subscribes on a version that dropped the type parameter.
        Events.Result raw = Events.subscribe("fake.ClientReceiveMessageEvents", "RAW",
                (proxy, method, args) -> null);
        check("a raw field falls back to the nested interface OR fails cleanly: "
                + raw.reason, raw.ok || raw.reason.contains("could not work out"));

        // Failures have to name what was wrong, not shrug.
        Events.Result noClass = Events.subscribe("fake.NotThere", "GAME",
                (proxy, method, args) -> null);
        check("a missing class is named", !noClass.ok && noClass.reason.contains("fake.NotThere"));

        Events.Result noField = Events.subscribe("fake.ClientReceiveMessageEvents", "NOPE",
                (proxy, method, args) -> null);
        check("a missing field is named", !noField.ok && noField.reason.contains("NOPE"));

        // The name mapping the fallback relies on.
        check("GAME -> Game", Events.camel("GAME").equals("Game"));
        check("MODIFY_GAME -> ModifyGame", Events.camel("MODIFY_GAME").equals("ModifyGame"));

        System.out.println(fails == 0 ? "OK" : fails + " FAILED");
    }
}
