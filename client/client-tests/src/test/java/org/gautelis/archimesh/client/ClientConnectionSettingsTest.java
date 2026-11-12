package org.gautelis.archimesh.client;

import org.gautelis.archimesh.plugin.util.ClientConnectionSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionSettingsTest {
    @Test void interactiveDialogsUseTheConfiguredStartupUrlAndPreferAnActiveConnection() {
        String key = "archimesh.startup.pull.wsBaseUrl";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, " ws://127.0.0.1:64680 ");
            assertEquals("ws://127.0.0.1:64680", ClientConnectionSettings.wsBaseUrl(null));
            assertEquals("ws://127.0.0.1:64680", ClientConnectionSettings.wsBaseUrl(""));
            assertEquals("ws://127.0.0.1:12345", ClientConnectionSettings.wsBaseUrl("ws://127.0.0.1:12345"));
            System.clearProperty(key);
            String environment = System.getenv("ARCHIMESH_STARTUP_PULL_WS_BASE_URL");
            assertEquals(environment == null || environment.isBlank() ? "ws://localhost:8081" : environment.trim(),
                         ClientConnectionSettings.wsBaseUrl(null));
        } finally {
            if(previous == null) System.clearProperty(key); else System.setProperty(key,previous);
        }
    }
}
