package dev.skullzz.mirage.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import dev.skullzz.mirage.Mirage;

/**
 * Listening to chat, without naming anything that could have moved.
 *
 * <p>The event that carries a chat message lives in a versioned Fabric package, and this
 * mod has never had it on its classpath. Naming it would be a fifth guess, and a wrong one
 * is a build that does not compile at all -- so the class is looked up by name at runtime,
 * the callback is a {@link Proxy} so its shape cannot matter either, and the message is
 * picked out of the arguments by asking each one for its text.
 *
 * <p>When it cannot be hooked, it says so and says what it could not find. The tracker
 * then simply counts nothing, which is visible, rather than counting wrongly, which is not.
 */
public final class ChatHook {

    /** Where the chat events have lived. Tried in order. */
    private static final String[] EVENT_CLASSES = {
        "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents",
    };

    /** The fields on it worth subscribing to: a server message, and a chat message. */
    private static final String[] EVENT_FIELDS = { "GAME", "CHAT", "MODIFY_GAME" };

    private static boolean attached;
    private static String reason = "not attached yet";

    private ChatHook() {
    }

    public static boolean attached() {
        return attached;
    }

    public static String reason() {
        return reason;
    }

    /** Subscribes to whichever chat event this version has. */
    public static void register() {
        for (String className : EVENT_CLASSES) {
            Class<?> events;
            try {
                events = Class.forName(className);
            } catch (ClassNotFoundException missing) {
                reason = "no class " + className;
                continue;
            }

            for (String fieldName : EVENT_FIELDS) {
                if (subscribe(events, fieldName)) {
                    attached = true;
                    reason = "listening through " + events.getSimpleName() + "." + fieldName;
                    return;
                }
            }
            reason = "found " + events.getSimpleName() + " but none of "
                    + String.join(", ", EVENT_FIELDS) + " could be subscribed to";
        }
        Mirage.LOGGER.warn("Mirage could not listen to chat: {}", reason);
    }

    @SuppressWarnings("unchecked")
    private static boolean subscribe(Class<?> events, String fieldName) {
        try {
            Field field = events.getField(fieldName);
            Object event = field.get(null);
            if (event == null) return false;

            // The event's own register method takes the listener interface; that interface
            // is whatever the field's type says it is, and is never named here.
            Method register = null;
            for (Method candidate : event.getClass().getMethods()) {
                if (candidate.getName().equals("register")
                        && candidate.getParameterCount() == 1) {
                    register = candidate;
                    break;
                }
            }
            if (register == null) return false;

            Class<?> listenerType = register.getParameterTypes()[0];
            if (!listenerType.isInterface()) return false;

            Object listener = Proxy.newProxyInstance(
                    ChatHook.class.getClassLoader(),
                    new Class<?>[] { listenerType },
                    (proxy, method, args) -> {
                        // A proxy is handed Object's own methods as well.
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "mirage chat";
                            };
                        }

                        take(args);

                        // Whatever this callback is supposed to answer, answer the thing
                        // that changes nothing: true for a filter, null for a listener.
                        Class<?> returns = method.getReturnType();
                        if (returns == boolean.class || returns == Boolean.class) return true;
                        return null;
                    });

            register.invoke(event, listener);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reason = fieldName + ": " + failure;
            return false;
        }
    }

    /**
     * Finds the message among whatever the callback was handed.
     *
     * <p>By asking rather than by position: what a chat callback carries has changed
     * between versions, and only one of the arguments has text in it.
     */
    private static void take(Object[] args) {
        if (args == null) return;

        for (Object argument : args) {
            if (argument == null) continue;
            String line = textOf(argument);
            if (line == null || line.isEmpty()) continue;

            try {
                Sessions.offer(line);
            } catch (RuntimeException failure) {
                // A bad line must never take the chat callback down with it.
                Mirage.LOGGER.warn("Mirage stumbled on a chat line", failure);
            }
            return;
        }
    }

    /** An argument's text, if it has any. */
    private static String textOf(Object argument) {
        if (argument instanceof String text) return text;
        try {
            Method getString = argument.getClass().getMethod("getString");
            if (getString.getReturnType() == String.class) {
                return (String) getString.invoke(argument);
            }
        } catch (ReflectiveOperationException | RuntimeException notText) {
            // Not something with text in it; the next argument might be.
        }
        return null;
    }
}
