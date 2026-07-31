package dev.khoa.plugin.cookieportal.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Folia regional scheduler implementation utilizing reflective invocation for multi-threaded region dispatching.
 */
public final class FoliaPortalScheduler implements PortalScheduler {

    private final JavaPlugin plugin;
    private final Object globalScheduler;
    private final Object regionScheduler;
    private final Method globalExecute;
    private final Method globalRunDelayed;
    private final Method globalRunAtFixedRate;
    private final Method regionExecute;
    private final Method regionRunDelayed;
    private final Set<ReflectionTask> tasks = ConcurrentHashMap.newKeySet();

    public FoliaPortalScheduler(JavaPlugin plugin) {
        this.plugin = plugin;

        try {
            Object server = Bukkit.getServer();
            Method getGlobal = server.getClass().getMethod("getGlobalRegionScheduler");
            Method getRegion = server.getClass().getMethod("getRegionScheduler");
            this.globalScheduler = getGlobal.invoke(server);
            this.regionScheduler = getRegion.invoke(server);
            this.globalExecute = findMethod(this.globalScheduler.getClass(), "execute", 2, Runnable.class);
            this.globalRunDelayed = findMethod(this.globalScheduler.getClass(), "runDelayed", 3, Consumer.class);
            this.globalRunAtFixedRate = findMethod(this.globalScheduler.getClass(), "runAtFixedRate", 4, Consumer.class);
            this.regionExecute = findMethod(this.regionScheduler.getClass(), "execute", 3, Runnable.class);
            this.regionRunDelayed = findMethod(this.regionScheduler.getClass(), "runDelayed", 4, Consumer.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to initialize Folia region scheduler", exception);
        }
    }

    public static boolean isAvailable() {
        try {
            Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            Bukkit.getServer().getClass().getMethod("getRegionScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    @Override
    public PortalScheduler.Task runGlobal(Runnable action) {
        invokeVoid(this.globalExecute, this.globalScheduler, this.plugin, action);
        return CompletedTask.INSTANCE;
    }

    @Override
    public PortalScheduler.Task runGlobalLater(Runnable action, long delayTicks) {
        Consumer<Object> consumer = ignored -> this.runSafely(action, "global delayed task");
        Object scheduled = invoke(this.globalRunDelayed, this.globalScheduler, this.plugin, consumer, Math.max(1L, delayTicks));
        return this.track(scheduled);
    }

    @Override
    public PortalScheduler.Task runGlobalTimer(Runnable action, long delayTicks, long periodTicks) {
        Consumer<Object> consumer = ignored -> this.runSafely(action, "global repeating task");
        Object scheduled = invoke(this.globalRunAtFixedRate, this.globalScheduler, this.plugin, consumer, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        return this.track(scheduled);
    }

    @Override
    public PortalScheduler.Task runForPlayer(Player player, Runnable action) {
        return this.runForEntity(player, action);
    }

    @Override
    public PortalScheduler.Task runForEntity(Entity entity, Runnable action) {
        Object scheduler = this.entityScheduler(entity);
        Method run = findMethodUnchecked(scheduler.getClass(), "run", 3, Consumer.class);
        Consumer<Object> consumer = ignored -> this.runSafely(action, "entity task");
        Object scheduled = invoke(run, scheduler, this.plugin, consumer, (Runnable) () -> {});
        return this.track(scheduled);
    }

    @Override
    public PortalScheduler.Task runForPlayerLater(Player player, Runnable action, long delayTicks) {
        Object scheduler = this.entityScheduler(player);
        Method runDelayed = findMethodUnchecked(scheduler.getClass(), "runDelayed", 4, Consumer.class);
        Consumer<Object> consumer = ignored -> this.runSafely(action, "delayed player task");
        Object scheduled = invoke(runDelayed, scheduler, this.plugin, consumer, (Runnable) () -> {}, Math.max(1L, delayTicks));
        return this.track(scheduled);
    }

    @Override
    public PortalScheduler.Task runAt(Location location, Runnable action) {
        invokeVoid(this.regionExecute, this.regionScheduler, this.plugin, location, action);
        return CompletedTask.INSTANCE;
    }

    @Override
    public PortalScheduler.Task runAtLater(Location location, Runnable action, long delayTicks) {
        Consumer<Object> consumer = ignored -> this.runSafely(action, "region delayed task");
        Object scheduled = invoke(this.regionRunDelayed, this.regionScheduler, this.plugin, location, consumer, Math.max(1L, delayTicks));
        return this.track(scheduled);
    }

    @Override
    public void cancelAll() {
        for (ReflectionTask task : this.tasks) {
            task.cancel();
        }
        this.tasks.clear();
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    private Object entityScheduler(Entity entity) {
        try {
            Method method = entity.getClass().getMethod("getScheduler");
            return method.invoke(entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to obtain Folia EntityScheduler", exception);
        }
    }

    private void runSafely(Runnable action, String description) {
        try {
            action.run();
        } catch (Throwable throwable) {
            this.plugin.getLogger().log(Level.SEVERE, "Error executing " + description, throwable);
        }
    }

    private PortalScheduler.Task track(Object scheduledTask) {
        if (scheduledTask == null) {
            return CompletedTask.INSTANCE;
        } else {
            ReflectionTask task = new ReflectionTask(scheduledTask);
            this.tasks.add(task);
            return task;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount, Class<?> requiredParameter) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                boolean containsRequired = false;
                for (Class<?> parameter : method.getParameterTypes()) {
                    if (requiredParameter.isAssignableFrom(parameter)) {
                        containsRequired = true;
                        break;
                    }
                }
                if (containsRequired) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Method findMethodUnchecked(Class<?> type, String name, int parameterCount, Class<?> requiredParameter) {
        try {
            return findMethod(type, name, parameterCount, requiredParameter);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            } else {
                throw new IllegalStateException(cause);
            }
        }
    }

    private static void invokeVoid(Method method, Object target, Object... arguments) {
        invoke(method, target, arguments);
    }

    private final class ReflectionTask implements PortalScheduler.Task {
        private final Object handle;
        private volatile boolean cancelled;

        private ReflectionTask(Object handle) {
            this.handle = handle;
        }

        @Override
        public void cancel() {
            if (!this.cancelled) {
                this.cancelled = true;
                try {
                    Method cancel = this.handle.getClass().getMethod("cancel");
                    cancel.setAccessible(true);
                    cancel.invoke(this.handle);
                } catch (ReflectiveOperationException exception) {
                    FoliaPortalScheduler.this.plugin.getLogger().warning("Failed to cancel Folia task: " + exception.getMessage());
                } finally {
                    FoliaPortalScheduler.this.tasks.remove(this);
                }
            }
        }

        @Override
        public boolean cancelled() {
            return this.cancelled;
        }
    }

    private enum CompletedTask implements PortalScheduler.Task {
        INSTANCE;

        @Override
        public void cancel() {}

        @Override
        public boolean cancelled() {
            return true;
        }
    }
}
