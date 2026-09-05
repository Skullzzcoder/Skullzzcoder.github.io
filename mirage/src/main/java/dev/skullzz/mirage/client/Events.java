package dev.skullzz.mirage.client;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * Subscribing to a Fabric event without naming it at compile time.
 *
 * <p>The events this mod needs from Fabric live in versioned packages it has never had on
 * its classpath, and a guessed name is a build that will not compile at all. So the class
 * is looked up by name, the listener is a {@link Proxy} so the callback's shape cannot
 * matter either, and a failure is a message rather than a crash.
 *
 * <p>The part that is easy to get wrong, and was: {@code Event.register(T)} erases to
 * {@code register(Object)}, so asking the register method what interface it wants gives
 * {@code Object} and nothing ever subscribes. The interface has to come from the field's
 * generic type -- {@code Event<ClientReceiveMessageEvents.Game>} -- which survives
 * erasure because it is written down in the class file.
 */
public final class Events {

    /** What happened, in words, whether it worked or not. */
    public static final class Result {
        public final boolean ok;
        public final String reason;

        Result(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    private Events() {
    }

    /**
     * Subscribes {@code body} to {@code className.fieldName}.
     *
     * @param body called with the callback's arguments; its return value is used only if
     *             the callback expects one
     */
    public static Result subscribe(String className, String fieldName, InvocationHandler body) {
        Class<?> owner;
        try {
            owner = Class.forName(className);
        } catch (ClassNotFoundException | RuntimeException missing) {
            return new Result(false, "no class " + className);
        }

        Field field;
        try {
            field = owner.getField(fieldName);
        } catch (NoSuchFieldException | RuntimeException missing) {
            return new Result(false, owner.getSimpleName() + " has no " + fieldName);
        }

        Object event;
        try {
            event = field.get(null);
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return new Result(false, fieldName + " could not be read: " + unreadable);
        }
        if (event == null) return new Result(false, fieldName + " is null");

        Class<?> listenerType = listenerOf(field, owner, fieldName);
        if (listenerType == null) {
            return new Result(false, "could not work out what listener " + fieldName
                    + " wants");
        }

        Method register = registerOn(event);
        if (register == null) {
            return new Result(false, fieldName + " has no register method");
        }

        try {
            Object listener = Proxy.newProxyInstance(
                    Events.class.getClassLoader(), new Class<?>[] { listenerType },
                    (proxy, method, args) -> {
                        // A proxy is handed Object's own methods too.
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "mirage listener";
                            };
                        }
                        return body.invoke(proxy, method, args);
                    });
            register.invoke(event, listener);
            return new Result(true, "listening through " + owner.getSimpleName() + "."
                    + fieldName);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return new Result(false, fieldName + ": " + failure);
        }
    }

    /**
     * What interface the event's listeners implement.
     *
     * <p>From the field's generic type first, since that is the reliable answer. Falling
     * back to a nested interface whose name matches the field, because a field named GAME
     * on a class with a nested {@code Game} interface is not a coincidence.
     */
    static Class<?> listenerOf(Field field, Class<?> owner, String fieldName) {
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> type
                    && type.isInterface()) {
                return type;
            }
        }

        String wanted = camel(fieldName);
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.isInterface() && nested.getSimpleName().equalsIgnoreCase(wanted)) {
                return nested;
            }
        }
        return null;
    }

    /** GAME becomes Game, MODIFY_GAME becomes ModifyGame. */
    public static String camel(String constant) {
        StringBuilder out = new StringBuilder();
        for (String part : constant.split("_")) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * The event's register method, in a form that can actually be called.
     *
     * <p>Found by shape, not by parameter type: on a generic event it erases to
     * {@code register(Object)}, so looking for one that takes an interface finds nothing.
     *
     * <p>And found on a class that is allowed to be called, which is the part that bites.
     * An event object is an instance of some implementation class that is not public --
     * Fabric's live in an impl package -- so reflecting the method off the runtime class
     * gives one whose declaring class is inaccessible, and invoking it throws
     * IllegalAccessException even though the method itself says public. The same method
     * declared on the public supertype works. Where there is no such supertype, asking
     * for access is the last resort.
     */
    static Method registerOn(Object event) {
        Method fallback = null;

        for (Method candidate : event.getClass().getMethods()) {
            if (!candidate.getName().equals("register") || candidate.getParameterCount() != 1) {
                continue;
            }
            if (java.lang.reflect.Modifier.isPublic(
                    candidate.getDeclaringClass().getModifiers())) {
                return candidate;
            }
            if (fallback == null) fallback = candidate;
        }

        // Declared on something not public. Look for the same method on a public
        // ancestor, which is where the API actually declares it.
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            Method found = declaredRegister(type);
            if (found != null) return found;
            for (Class<?> face : type.getInterfaces()) {
                found = declaredRegister(face);
                if (found != null) return found;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                return fallback;
            } catch (RuntimeException refused) {
                return fallback;
            }
        }
        return null;
    }

    /** A one-argument register declared directly on a public type, or nothing. */
    private static Method declaredRegister(Class<?> type) {
        if (!java.lang.reflect.Modifier.isPublic(type.getModifiers())) return null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals("register") && candidate.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(candidate.getModifiers())) {
                return candidate;
            }
        }
        return null;
    }
}
