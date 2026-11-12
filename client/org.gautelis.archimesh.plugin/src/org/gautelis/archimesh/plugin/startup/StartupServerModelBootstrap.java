package org.gautelis.archimesh.plugin.startup;

import java.util.UUID;
import java.io.IOException;
import java.util.List;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.ui.ModelCatalogClient;
import org.gautelis.archimesh.plugin.util.ClientConnectionSettings;
import org.gautelis.archimesh.plugin.ws.ArchimeshSessionManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

/**
 * Optional startup bootstrap that opens a model from the Archimesh server.
 * This flow is opt-in via system properties or environment variables.
 */
final class StartupServerModelBootstrap {

    private static final String PROP_ENABLED = "archimesh.startup.pull.enabled";
    private static final String PROP_MODEL_ID = "archimesh.startup.pull.modelId";
    private static final String PROP_USER_ID = "archimesh.startup.pull.userId";
    private static final String PROP_SESSION_ID = "archimesh.startup.pull.sessionId";
    private static final String PROP_MODEL_NAME = "archimesh.startup.pull.modelName";
    private static final String PROP_MODEL_REF = "archimesh.startup.pull.modelRef";
    private static final String PROP_AUTH_TOKEN = "archimesh.startup.pull.authToken";

    private static final String ENV_ENABLED = "ARCHIMESH_STARTUP_PULL_ENABLED";
    private static final String ENV_MODEL_ID = "ARCHIMESH_STARTUP_PULL_MODEL_ID";
    private static final String ENV_USER_ID = "ARCHIMESH_STARTUP_PULL_USER_ID";
    private static final String ENV_SESSION_ID = "ARCHIMESH_STARTUP_PULL_SESSION_ID";
    private static final String ENV_MODEL_NAME = "ARCHIMESH_STARTUP_PULL_MODEL_NAME";
    private static final String ENV_MODEL_REF = "ARCHIMESH_STARTUP_PULL_MODEL_REF";
    private static final String ENV_AUTH_TOKEN = "ARCHIMESH_STARTUP_PULL_AUTH_TOKEN";

    private static final String DEFAULT_USER_ID = "anonymous";

    void maybeBootstrap(ArchimeshPlugin plugin) {
        StartupConfig config = StartupConfig.read();
        if(!config.enabled()) {
            return;
        }
        if(config.modelId().isBlank()) {
            ArchimeshPlugin.logInfo("Startup server-pull enabled but modelId is missing; skipping");
            return;
        }
        if(!isKnownServerModel(config)) {
            return;
        }

        Display display = resolveDisplay();
        if(display == null || display.isDisposed()) {
            ArchimeshPlugin.logInfo("Startup server-pull requested but UI display is unavailable; skipping");
            return;
        }

        display.asyncExec(() -> bootstrapOnUiThread(plugin, config));
    }

    private void bootstrapOnUiThread(ArchimeshPlugin plugin, StartupConfig config) {
        if(plugin == null || plugin.getSessionManager() == null) {
            return;
        }
        if(!PlatformUI.isWorkbenchRunning()) {
            ArchimeshPlugin.logInfo("Startup server-pull skipped: workbench is not running");
            return;
        }
        if(!IEditorModelManager.INSTANCE.getModels().isEmpty()) {
            ArchimeshPlugin.logInfo("Startup server-pull skipped: model(s) already open");
            return;
        }

        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        String effectiveName = config.modelName().isBlank()
                ? "Archimesh: " + config.modelId()
                : config.modelName();
        if(!"HEAD".equalsIgnoreCase(config.modelRef())) {
            effectiveName = effectiveName + " @" + config.modelRef();
        }
        model.setName(effectiveName);
        if(model instanceof IIdentifier identifier) {
            identifier.setId(config.modelId());
        }

        IEditorModelManager.INSTANCE.openModel(model);

        ArchimeshSessionManager sessionManager = plugin.getSessionManager();
        sessionManager.setActor(config.userId(), config.sessionId());
        sessionManager.setAuthToken(config.authToken());
        sessionManager.setServerBackedSession(true);
        sessionManager.connect(config.wsBaseUrl(), config.modelId(), config.modelRef(), true);
        if(sessionManager.isConnected()) {
            sessionManager.attachModel(model);
            ArchimeshPlugin.logInfo("Startup server-pull connected modelId=" + config.modelId()
                    + " ref=" + config.modelRef());
        }
        else {
            ArchimeshPlugin.logInfo("Startup server-pull failed to connect modelId=" + config.modelId()
                    + " ref=" + config.modelRef());
        }
    }

    private Display resolveDisplay() {
        if(PlatformUI.isWorkbenchRunning()) {
            return PlatformUI.getWorkbench().getDisplay();
        }
        return Display.getDefault();
    }

    private boolean isKnownServerModel(StartupConfig config) {
        try {
            List<ModelCatalogClient.ModelOption> models = ModelCatalogClient.fetchModels(config.wsBaseUrl(), config.authToken());
            boolean found = models.stream().anyMatch(model -> config.modelId().equals(model.modelId()));
            if(!found) {
                ArchimeshPlugin.logInfo("Startup server-pull skipped: modelId not found in central catalog modelId=" + config.modelId());
            }
            return found;
        }
        catch(IOException | InterruptedException ex) {
            if(ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ArchimeshPlugin.logInfo("Startup server-pull skipped: could not verify central model catalog: " + ex.getMessage());
            return false;
        }
    }

    private record StartupConfig(
            boolean enabled,
            String wsBaseUrl,
            String modelId,
            String modelRef,
            String userId,
            String sessionId,
            String modelName,
            String authToken) {

        private static StartupConfig read() {
            boolean enabled = parseBoolean(readValue(PROP_ENABLED, ENV_ENABLED), false);
            String wsBaseUrl = ClientConnectionSettings.wsBaseUrl(null);
            String modelId = defaultIfBlank(readValue(PROP_MODEL_ID, ENV_MODEL_ID), "");
            String modelRef = defaultIfBlank(readValue(PROP_MODEL_REF, ENV_MODEL_REF), "HEAD");
            String userId = defaultIfBlank(readValue(PROP_USER_ID, ENV_USER_ID), DEFAULT_USER_ID);
            String configuredSessionId = defaultIfBlank(readValue(PROP_SESSION_ID, ENV_SESSION_ID), "");
            String sessionId = configuredSessionId.isBlank()
                    ? "archi-startup-" + UUID.randomUUID()
                    : configuredSessionId;
            String modelName = defaultIfBlank(readValue(PROP_MODEL_NAME, ENV_MODEL_NAME), "");
            String authToken = defaultIfBlank(readValue(PROP_AUTH_TOKEN, ENV_AUTH_TOKEN), "");
            return new StartupConfig(enabled, wsBaseUrl, modelId, modelRef, userId, sessionId, modelName, authToken);
        }
    }

    private static String readValue(String propertyKey, String envKey) {
        String property = System.getProperty(propertyKey);
        if(property != null && !property.isBlank()) {
            return property.trim();
        }
        String env = System.getenv(envKey);
        return env == null ? "" : env.trim();
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if(value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
