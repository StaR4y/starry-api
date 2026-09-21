package tech.starryapi.plugin;

record PluginCleanupReport(int resourceFailures, int pendingResources, boolean tasksTerminated, int pendingRequests) {
    boolean complete() {
        return resourceFailures == 0 && pendingResources == 0 && tasksTerminated && pendingRequests == 0;
    }
}
