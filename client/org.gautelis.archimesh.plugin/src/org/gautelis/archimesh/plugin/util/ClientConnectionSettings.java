package org.gautelis.archimesh.plugin.util;

/** Shared server URL defaults for startup and interactive connection dialogs. */
public final class ClientConnectionSettings {
    private ClientConnectionSettings() { }

    public static String wsBaseUrl(String currentServer) {
        if(currentServer != null && !currentServer.isBlank()) return currentServer.trim();
        String configured = System.getProperty("archimesh.startup.pull.wsBaseUrl");
        if(configured == null || configured.isBlank()) configured = System.getenv("ARCHIMESH_STARTUP_PULL_WS_BASE_URL");
        return configured == null || configured.isBlank() ? "ws://localhost:8081" : configured.trim();
    }
}
