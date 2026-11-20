package pl.zelekdev.serverstyler.client;

import pl.zelekdev.serverstyler.config.ConfigManager;
import pl.zelekdev.serverstyler.config.PresetStore;
import pl.zelekdev.serverstyler.render.RenderCache;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Owns the process-wide {@link ConfigManager} and
 * {@link RenderCache} instances that the mixins read from.
 */
public final class ServerStylerClient implements ClientModInitializer {

	public static final String MOD_ID = "serverstyler";
	public static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler");

	private static ConfigManager config;
	private static PresetStore presets;
	private static RenderCache renderCache;

	@Override
	public void onInitializeClient() {
		config = new ConfigManager();
		config.load();
		presets = new PresetStore();
		presets.load();
		renderCache = new RenderCache(config);
		LOGGER.info("ServerStyler client initialised.");
		if (config.wasMigrated()) {
			LOGGER.info("Config upgraded from schema v{} to v{}. A copy of the previous "
							+ "file was kept alongside servers.json.",
					config.detectedSchemaVersion(), config.schemaVersion());
		}
		if (config.isReadOnly()) {
			LOGGER.warn("{} was written by a newer ServerStyler build (schema v{} > v{}). "
							+ "Styles load and render, but changes will not be written to disk "
							+ "to avoid destroying fields this build does not understand.",
					config.getConfigPath().getFileName(), config.detectedSchemaVersion(),
					config.schemaVersion());
		}
		installShutdownFlush();
	}

	/**
	 * Flushes any pending async write when the process exits.
	 *
	 * <p>The save executor is a daemon pool, so the JVM does not wait for it: a
	 * player who edits a style and quits within the write interval would otherwise
	 * lose the last change. A shutdown hook is used instead of a
	 * {@code ClientLifecycleEvents} callback because the hook exists in every
	 * Minecraft version this mod targets, while the event names have moved between
	 * releases.</p>
	 */
	private static void installShutdownFlush() {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				ConfigManager current = config;
				PresetStore currentPresets = presets;
				try {
					// Distinguishes "quit with unsaved edits" from a plain quit: the latter
					// must not rewrite servers.json at all.
					if (current != null) {
						current.flushIfDirty();
					}
					if (currentPresets != null) {
						currentPresets.flushIfDirty();
					}
				} catch (RuntimeException e) {
					LOGGER.warn("Could not flush ServerStyler config on shutdown: {}",
							e.getMessage());
				}
			}, "ServerStyler-ShutdownFlush"));
		} catch (IllegalStateException | SecurityException e) {
			// Already shutting down, or a security manager forbids hooks: the async
			// writer still handles the common case.
			LOGGER.debug("Skipping ServerStyler shutdown flush: {}", e.getMessage());
		}
	}

	public static ConfigManager config() {
		return config;
	}

	public static PresetStore presets() {
		return presets;
	}

	public static RenderCache renderCache() {
		return renderCache;
	}
}