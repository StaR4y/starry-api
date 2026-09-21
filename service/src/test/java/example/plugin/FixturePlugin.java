package example.plugin;

import org.pf4j.PluginWrapper;
import tech.starryapi.plugin.sdk.PluginContext;
import tech.starryapi.plugin.sdk.StarryPlugin;

public final class FixturePlugin extends StarryPlugin {
    private static final String GENERATION = java.util.UUID.randomUUID().toString();
    private boolean failStop;
    static { System.setProperty("starry.plugin.fixture.loaded", "true"); }
    public FixturePlugin(PluginWrapper wrapper) { super(wrapper); }
    @Override public void start() {
        String config = context().configJson();
        failStop = config.contains("stopFail");
        if (config.contains("blockStart")) awaitRelease();
        if (config.contains("stubbornTask")) {
            context().tasks().schedule("stubborn", java.time.Duration.ZERO, () -> {
                awaitRelease();
                ((java.util.concurrent.CountDownLatch) System.getProperties().get("starry.plugin.fixture.exited")).countDown();
            });
        }
        if (config.contains("closeFail")) context().manage(() -> { throw new IllegalStateException("private-close-value"); });
        if (config.contains("managed")) {
            var task = context().tasks().schedule("pending", java.time.Duration.ofDays(1), () -> {});
            context().manage(() -> {
                int closed = Integer.parseInt(System.getProperty("starry.plugin.fixture.closed", "0"));
                System.setProperty("starry.plugin.fixture.closed", Integer.toString(closed + 1));
                System.setProperty("starry.plugin.fixture.taskDisposed", Boolean.toString(task.isDisposed()));
            });
        }
        if (context().configJson().contains("fail")) throw new AssertionError("test failure");
        context().log(PluginContext.Level.INFO, "fixture start");
    }
    @Override public void stop() {
        context().log(PluginContext.Level.INFO, "fixture stop");
        if (failStop && !Boolean.getBoolean("starry.plugin.fixture.recovered")) throw new IllegalStateException("private-stop-value");
    }
    @Override public String debug(String command, String input) {
        if (command.equals("fail")) throw new IllegalStateException("secret debug input: " + input);
        if (command.equals("generation")) return "{\"generation\":\"" + GENERATION + "\"}";
        return context().configJson();
    }

    private static void awaitRelease() {
        var entered = (java.util.concurrent.CountDownLatch) System.getProperties().get("starry.plugin.fixture.entered");
        var release = (java.util.concurrent.CountDownLatch) System.getProperties().get("starry.plugin.fixture.release");
        entered.countDown();
        // Deliberately non-cooperative code exercises the host's timeout boundary.
        while (release.getCount() > 0) {
            try { release.await(); } catch (InterruptedException ignored) { }
        }
    }
}
