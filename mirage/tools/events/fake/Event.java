package fake;

/** Shaped like Fabric's: register(T) erases to register(Object). */
public abstract class Event<T> {
    public abstract void register(T listener);
}
