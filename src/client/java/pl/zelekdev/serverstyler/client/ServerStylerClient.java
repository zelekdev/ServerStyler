package pl.zelekdev.serverstyler.client;

import pl.zelekdev.serverstyler.config.ConfigManager;
import pl.zelekdev.serverstyler.config.PresetStore;
import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Lang;
import pl.zelekdev.serverstyler.i18n.LangManager;
import pl.zelekdev.serverstyler.render.RenderCache;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Owns the process-wide {@link ConfigManager},
 * {@link PresetStore}, {@link LangManager} and {@link RenderCache} instances that
 * the mixins read from.
 */
public final class ServerStylerClient implements ClientModInitializer {

	public static final String MOD_ID = "serverstyler";
	public static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler");

	private static ConfigManager config;
	private static PresetStore presets;
	private static LangManager lang;
	private static RenderCache renderCache;

	@Override
	public void onInitializeClient() {
		config = new ConfigManager();
		config.load();
		presets = new PresetStore();
		presets.load();
		// The language files are loaded before anything can render a label, and the
		// stored choice is applied on top of the manager's English default.
		lang = new LangManager();
		lang.load();
		lang.setCurrent(config.getLanguage());
		I18n.install(lang);
		renderCache = new RenderCache(config);
		LOGGER.info("ServerStyler client initialised (language: {}).", lang.current().code());
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
				LangManager currentLang = lang;
				try {
					// Distinguishes "quit with unsaved edits" from a plain quit: the latter
					// must not rewrite servers.json at all.
					if (current != null) {
						current.flushIfDirty();
					}
					if (currentPresets != null) {
						currentPresets.flushIfDirty();
					}
					if (currentLang != null) {
						currentLang.flushIfDirty();
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

	/**
	 * Applies a new interface language everywhere: the translation table, the
	 * persisted config and the on-disk language files.
	 *
	 * <p>Called from the ModMenu config screen. Kept here rather than in the screen
	 * so the four per-version copies share one implementation.</p>
	 */
	public static void applyLanguage(Lang chosen) {
		if (chosen == null) {
			return;
		}
		LangManager currentLang = lang;
		if (currentLang != null) {
			currentLang.setCurrent(chosen);
		}
		ConfigManager currentConfig = config;
		if (currentConfig != null) {
			currentConfig.setLanguage(chosen.code());
			currentConfig.saveAsync();
		}
	}

	public static ConfigManager config() {
		return config;
	}

	public static PresetStore presets() {
		return presets;
	}

	public static LangManager lang() {
		return lang;
	}

	public static RenderCache renderCache() {
		return renderCache;
	}
}
