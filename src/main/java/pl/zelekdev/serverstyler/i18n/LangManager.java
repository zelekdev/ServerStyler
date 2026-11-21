package pl.zelekdev.serverstyler.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Owns the player-facing text of the whole mod.
 *
 * <h3>Where the text lives</h3>
 * <p>Every language has its own file in the config directory, next to
 * {@code servers.json}:</p>
 * <pre>
 * config/serverstyler/
 *   servers.json          the styles + the chosen language
 *   presets.json          user presets
 *   lang/en.json          English strings
 *   lang/pl.json          Polish strings
 *   lang/de.json          German strings
 * </pre>
 *
 * <p>The files are <b>copied out of the jar on first launch</b> and then owned by
 * the player: editing {@code lang/pl.json} changes what the mod shows. The
 * bundled copy is only ever used as the source of missing keys, so a player who
 * deletes a line gets it back on the next launch instead of seeing a blank
 * button.</p>
 *
 * <h3>Why the files are separate from {@code servers.json}</h3>
 * <p>Translations are large, rarely change, and are the one part of the config a
 * player is likely to hand-edit. Keeping them out of the style file means a
 * translation edit can never corrupt a style, and a style migration never
 * rewrites a translation.</p>
 *
 * <h3>Schema updates</h3>
 * <p>Each file carries a {@code schemaVersion}. On load, {@link LangMigration}
 * bumps it and back-fills every key the bundled default defines but the file
 * does not &mdash; so a new build that adds a button automatically adds its
 * string to every existing translation file, while keeping the player's wording
 * for everything already there.</p>
 *
 * <h3>Choosing a language without ModMenu</h3>
 * <p>The chosen language is stored in {@code servers.json} as
 * {@code "language": "pl"}. A player without ModMenu edits that one line; the
 * value is read on startup and is deliberately forgiving ({@code "PL"},
 * {@code "pl_PL"} and {@code " pl "} all work).</p>
 */
public final class LangManager {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/Lang");

	private static final String SUB_DIR = "serverstyler";
	private static final String LANG_DIR = "lang";
	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";

	/** Classpath directory holding the bundled defaults. */
	private static final String BUNDLED_ROOT = "/assets/serverstyler/i18n/";

	/** Largest translation file that will be read. */
	public static final long MAX_FILE_BYTES = 512L * 1024L;

	/** Maximum nesting depth accepted before parsing. */
	public static final int MAX_JSON_DEPTH = 8;

	private final Path langDir;
	private final Gson gson = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();
	private final Executor ioExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ServerStyler-LangIo");
		t.setDaemon(true);
		return t;
	});

	private final Map<Lang, Translations> tables = new EnumMap<>(Lang.class);
	private final Map<Lang, Map<String, String>> bundled = new EnumMap<>(Lang.class);

	private volatile Lang current = Lang.EN;
	private volatile boolean loaded = false;
	private volatile boolean dirty = false;

	public LangManager() {
		this(FabricLoader.getInstance().getConfigDir());
	}

	/** Test/alternative-location constructor. */
	public LangManager(Path baseConfigDir) {
		this.langDir = baseConfigDir.resolve(SUB_DIR).resolve(LANG_DIR);
	}

	public Path getLangDir() {
		return langDir;
	}

	// ------------------------------------------------------------------
	// Loading
	// ------------------------------------------------------------------

	/**
	 * Loads every bundled default, then materialises and migrates the on-disk
	 * copies. Safe to call once at client init.
	 */
	public synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;

		readBundledDefaults();

		try {
			Files.createDirectories(langDir);
		} catch (IOException e) {
			LOGGER.error("Could not create the ServerStyler language directory: {}",
					e.getMessage());
			// Still usable: the bundled defaults are in memory.
			buildTablesFromBundled();
			return;
		}

		for (Lang lang : Lang.values()) {
			loadOne(lang);
		}
		rebuildTables();
	}

	/** Reads every {@code assets/serverstyler/i18n/<code>.json} from the jar. */
	private void readBundledDefaults() {
		for (Lang lang : Lang.values()) {
			Map<String, String> defaults = readBundled(lang);
			bundled.put(lang, defaults);
			if (defaults.isEmpty()) {
				LOGGER.warn("Bundled translations for '{}' are missing or empty.", lang.code());
			}
		}
	}

	private Map<String, String> readBundled(Lang lang) {
		String resource = BUNDLED_ROOT + lang.code() + ".json";
		try (InputStream in = LangManager.class.getResourceAsStream(resource)) {
			if (in == null) {
				return new LinkedHashMap<>();
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonElement el = JsonParser.parseReader(r);
				if (!(el instanceof JsonObject root)) {
					return new LinkedHashMap<>();
				}
				return extractStrings(root);
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read bundled translations '{}': {}", resource, e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	/**
	 * Loads one language: copies the bundled default out if the file is missing,
	 * otherwise migrates it in place and writes the result back when anything
	 * changed.
	 */
	private void loadOne(Lang lang) {
		Path file = langDir.resolve(lang.code() + ".json");
		Map<String, String> defaults = bundled.getOrDefault(lang, new LinkedHashMap<>());

		if (!Files.exists(file)) {
			LOGGER.info("Creating {} from the bundled default.", file.getFileName());
			writeFile(file, buildFreshRoot(lang, defaults));
			return;
		}

		JsonObject root = tryParse(file);
		if (root == null) {
			recoverCorrupt(file);
			writeFile(file, buildFreshRoot(lang, defaults));
			return;
		}

		int detected = LangMigration.readVersion(root);
		if (LangMigration.isNewerThanBuild(detected)) {
			// Written by a newer build: read it, never rewrite it.
			LOGGER.warn("{} uses translation schema v{}, newer than this build's v{}. "
							+ "Loading it read-only.",
					file.getFileName(), detected, LangMigration.LATEST);
			return;
		}

		String applied = LangMigration.migrate(root, defaults);
		if (!applied.isEmpty()) {
			LOGGER.info("Updated {}: {}", file.getFileName(), applied);
			writeFile(file, root);
		}
	}

	private JsonObject buildFreshRoot(Lang lang, Map<String, String> defaults) {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", LangMigration.LATEST);
		root.addProperty("language", lang.code());
		JsonObject strings = new JsonObject();
		for (Map.Entry<String, String> entry : defaults.entrySet()) {
			strings.addProperty(entry.getKey(), entry.getValue());
		}
		root.add("strings", strings);
		return root;
	}

	private JsonObject tryParse(Path file) {
		try {
			long size = Files.size(file);
			if (size > MAX_FILE_BYTES) {
				LOGGER.warn("{} is {} bytes, above the {} byte cap; treating it as corrupt.",
						file.getFileName(), size, MAX_FILE_BYTES);
				return null;
			}
		} catch (IOException e) {
			LOGGER.warn("Could not measure {}: {}", file.getFileName(), e.getMessage());
			return null;
		}

		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[4096];
			long total = 0L;
			int read;
			while ((read = r.read(buffer)) != -1) {
				total += read;
				if (total > MAX_FILE_BYTES) {
					LOGGER.warn("{} grew past the {} byte cap while being read; "
							+ "treating it as corrupt.", file.getFileName(), MAX_FILE_BYTES);
					return null;
				}
				text.append(buffer, 0, read);
			}
			if (!withinDepth(text.toString(), MAX_JSON_DEPTH)) {
				LOGGER.warn("{} nests deeper than {} levels or has unbalanced brackets; "
						+ "treating it as corrupt.", file.getFileName(), MAX_JSON_DEPTH);
				return null;
			}
			JsonElement el = JsonParser.parseString(text.toString());
			return el instanceof JsonObject o ? o : null;
		} catch (JsonSyntaxException | IOException e) {
			LOGGER.warn("Failed to parse {}: {}", file.getFileName(), e.getMessage());
			return null;
		}
	}

	private void recoverCorrupt(Path file) {
		Path backup = file.resolveSibling(file.getFileName() + BAK_SUFFIX);
		try {
			Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("Recovered a corrupted {}: moved it to {} and will write a fresh copy.",
					file.getFileName(), backup.getFileName());
		} catch (IOException e) {
			LOGGER.error("Could not back up corrupted {}; leaving it in place: {}",
					file.getFileName(), e.getMessage());
		}
	}

	/** Rebuilds the in-memory tables from whatever is now on disk. */
	private void rebuildTables() {
		Translations english = null;
		for (Lang lang : Lang.values()) {
			Map<String, String> values = readStringsFromDisk(lang);
			if (values.isEmpty()) {
				values = bundled.getOrDefault(lang, new LinkedHashMap<>());
			}
			Translations table = new Translations(lang, values,
					lang == Lang.EN ? null : english);
			tables.put(lang, table);
			if (lang == Lang.EN) {
				english = table;
			}
		}
	}

	private void buildTablesFromBundled() {
		Translations english = null;
		for (Lang lang : Lang.values()) {
			Map<String, String> values = bundled.getOrDefault(lang, new LinkedHashMap<>());
			Translations table = new Translations(lang, values,
					lang == Lang.EN ? null : english);
			tables.put(lang, table);
			if (lang == Lang.EN) {
				english = table;
			}
		}
	}

	private Map<String, String> readStringsFromDisk(Lang lang) {
		Path file = langDir.resolve(lang.code() + ".json");
		if (!Files.exists(file)) {
			return new LinkedHashMap<>();
		}
		JsonObject root = tryParse(file);
		if (root == null) {
			return new LinkedHashMap<>();
		}
		return extractStrings(root);
	}

	/** Pulls the {@code strings} object out of a translation file root. */
	private static Map<String, String> extractStrings(JsonObject root) {
		Map<String, String> out = new LinkedHashMap<>();
		JsonElement el = root.get("strings");
		// v1 files kept the strings at the root level, so fall back to the root
		// object when there is no "strings" member.
		JsonObject strings = el instanceof JsonObject nested ? nested : root;
		for (Map.Entry<String, JsonElement> entry : strings.entrySet()) {
			String key = entry.getKey();
			if ("schemaVersion".equals(key) || "language".equals(key) || "strings".equals(key)) {
				continue;
			}
			if (entry.getValue() instanceof JsonPrimitive p && p.isString()) {
				String value = p.getAsString();
				if (value.length() > Translations.MAX_VALUE_LENGTH) {
					value = value.substring(0, Translations.MAX_VALUE_LENGTH);
				}
				out.put(key, value);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Language selection
	// ------------------------------------------------------------------

	/** The language currently in use. */
	public Lang current() {
		return current;
	}

	/**
	 * Switches the active language. The caller is responsible for persisting the
	 * choice (see {@code ConfigManager#setLanguage}); this only swaps the table.
	 */
	public void setCurrent(Lang lang) {
		this.current = lang == null ? Lang.EN : lang;
	}

	/** Switches by code, tolerating junk. Returns the language actually selected. */
	public Lang setCurrent(String code) {
		Lang lang = Lang.fromCode(code);
		this.current = lang;
		return lang;
	}

	// ------------------------------------------------------------------
	// Lookup
	// ------------------------------------------------------------------

	/** The table for the active language. */
	public Translations table() {
		Translations table = tables.get(current);
		if (table != null) {
			return table;
		}
		Translations english = tables.get(Lang.EN);
		if (english != null) {
			return english;
		}
		return new Translations(Lang.EN, new LinkedHashMap<>(), null);
	}

	/** The table for a specific language, falling back to English. */
	public Translations table(Lang lang) {
		Translations table = tables.get(lang);
		if (table != null) {
			return table;
		}
		Translations english = tables.get(Lang.EN);
		return english != null ? english : new Translations(Lang.EN, new LinkedHashMap<>(), null);
	}

	/** Translates {@code key} in the active language. */
	public String tr(String key) {
		return table().raw(key) != null ? table().raw(key) : key;
	}

	/** Translates {@code key} and substitutes {@code args}. */
	public String tr(String key, Object... args) {
		return table().format(key, args);
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	/** Marks the translation files as needing a rewrite (used after an edit). */
	public synchronized void markDirty() {
		dirty = true;
	}

	/** Writes every language file back, off-thread. */
	public void saveAsync() {
		if (!dirty) {
			return;
		}
		dirty = false;
		CompletableFuture.runAsync(this::saveAll, ioExecutor);
	}

	/** Writes every language file back, blocking. Used on shutdown. */
	public void saveNow() {
		dirty = false;
		saveAll();
	}

	/** Writes only when something changed. */
	public void flushIfDirty() {
		if (!dirty) {
			return;
		}
		dirty = false;
		saveAll();
	}

	private void saveAll() {
		for (Lang lang : Lang.values()) {
			Translations table = tables.get(lang);
			if (table == null) {
				continue;
			}
			JsonObject root = new JsonObject();
			root.addProperty("schemaVersion", LangMigration.LATEST);
			root.addProperty("language", lang.code());
			JsonObject strings = new JsonObject();
			for (Map.Entry<String, String> entry : table.entries().entrySet()) {
				strings.addProperty(entry.getKey(), entry.getValue());
			}
			root.add("strings", strings);
			writeFile(langDir.resolve(lang.code() + ".json"), root);
		}
	}

	private void writeFile(Path file, JsonObject root) {
		Path tmp = file.resolveSibling(file.getFileName() + TMP_SUFFIX);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				gson.toJson(root, w);
				w.flush();
			}
			atomicReplace(tmp, file);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to write {}: {}", file.getFileName(), e.getMessage());
		}
	}

	private void atomicReplace(Path tmp, Path target) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** Cheap pre-parse bracket-depth guard, mirroring {@code JsonUtils}. */
	private static boolean withinDepth(String text, int maxDepth) {
		if (text == null) {
			return true;
		}
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (c == '\\') {
					escaped = true;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == '{' || c == '[') {
				if (++depth > maxDepth) {
					return false;
				}
			} else if (c == '}' || c == ']') {
				if (--depth < 0) {
					return false;
				}
			}
		}
		return depth == 0;
	}

	/** Every key defined by the bundled English default; used by tests/diagnostics. */
	public List<String> knownKeys() {
		Map<String, String> english = bundled.get(Lang.EN);
		return english == null ? new ArrayList<>() : new ArrayList<>(english.keySet());
	}
}
