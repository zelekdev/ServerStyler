package pl.zelekdev.serverstyler.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Owns the named style presets: a fixed set of built-ins plus the user's own
 * saved styles, persisted to {@code config/serverstyler/presets.json}.
 *
 * <h3>Why a separate file</h3>
 * <p>{@code servers.json} is keyed by server address and is rewritten by the
 * server editor. Presets are keyed by a user-chosen name and are shared across
 * every server, so mixing them into the same map would mean inventing fake
 * addresses as keys and would make the "rename the server address" migration
 * path delete presets. A second file keeps both concerns independent and lets a
 * corrupt preset file be discarded without touching the player's server styles.</p>
 *
 * <h3>File layout</h3>
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "presets": [
 *     { "name": "Sunset", "style": { ...same shape as a server style... } }
 *   ]
 * }
 * </pre>
 *
 * <h3>Hardening</h3>
 * <ul>
 *   <li>File size capped at {@link #MAX_FILE_BYTES}; oversized files are treated
 *       as corrupt and moved aside.</li>
 *   <li>Bracket-depth guard before parsing, so a pathological file cannot blow
 *       the stack inside Gson.</li>
 *   <li>At most {@link #MAX_PRESETS} entries are read or written.</li>
 *   <li>Names are sanitised and truncated to {@link #MAX_NAME_LENGTH}.</li>
 *   <li>Writes are atomic ({@code .tmp} then move) and happen off-thread.</li>
 * </ul>
 */
public final class PresetStore {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/Presets");

	private static final String SUB_DIR = "serverstyler";
	private static final String FILE_NAME = "presets.json";
	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";

	/** Schema version written by this build. */
	public static final int SCHEMA_VERSION = 1;

	/** Longest accepted preset name, in characters. */
	public static final int MAX_NAME_LENGTH = 32;

	/** Hard cap on stored user presets. */
	public static final int MAX_PRESETS = 100;

	/** Largest preset file that will be read. */
	public static final long MAX_FILE_BYTES = 1L * 1024L * 1024L;

	/** Maximum JSON nesting accepted before parsing. */
	public static final int MAX_JSON_DEPTH = 24;

	/** A named style. The style is always a defensive copy. */
	public record Preset(String name, ServerStyle style) {
	}

	private final Path configDir;
	private final Path configPath;
	private final Gson gson;

	private final List<Preset> userPresets = new ArrayList<>();
	private final Executor ioExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ServerStyler-PresetIo");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean loaded = false;
	private volatile boolean dirty = false;

	public PresetStore() {
		this(FabricLoader.getInstance().getConfigDir());
	}

	/** Test/alternative-location constructor. */
	public PresetStore(Path baseConfigDir) {
		this.configDir = baseConfigDir.resolve(SUB_DIR);
		this.configPath = configDir.resolve(FILE_NAME);
		this.gson = ServerStyleConfig.createGson();
	}

	public Path getConfigPath() {
		return configPath;
	}

	// ------------------------------------------------------------------
	// Built-in presets
	// ------------------------------------------------------------------

	/**
	 * Ready-made styles offered in the editor.
	 *
	 * <p>Kept as a static list rather than data on disk: they must exist on a
	 * fresh install, must never be deleted by the user, and are cheap to build.
	 * Each one is a plain {@link ServerStyle}, so applying a built-in goes through
	 * exactly the same code path as applying a saved preset.</p>
	 */
	public static List<Preset> builtIns() {
		List<Preset> out = new ArrayList<>(12);

		out.add(new Preset("Sunset", gradient("#FF7A18", "#FF3D6E", "#FFC371")));
		out.add(new Preset("Ocean", gradient("#00C6FF", "#0072FF")));
		out.add(new Preset("Neon", gradient("#00F5A0", "#00D9F5", "#B14EFF")));
		out.add(new Preset("Fire", gradient("#FFE000", "#FF6A00", "#C40000")));
		out.add(new Preset("Ice", gradient("#E0F7FF", "#7FD8FF", "#2E7BFF")));
		out.add(new Preset("Candy", gradient("#FF9AE0", "#FFD1F0", "#9AE6FF")));
		out.add(new Preset("Forest", gradient("#A8E063", "#2E8B57", "#0B3D2E")));
		out.add(new Preset("Gold", solid("#FFD700", true)));
		out.add(new Preset("Blood", solid("#C1121F", true)));
		out.add(new Preset("Ghost", solid("#E8E8E8", false)));
		out.add(new Preset("Rainbow", gradient("#FF0000", "#FFA500", "#FFFF00", "#00FF00",
				"#00BFFF")));
		out.add(new Preset("Royal", gradient("#7B2FF7", "#F107A3")));

		return Collections.unmodifiableList(out);
	}

	private static ServerStyle gradient(String... colors) {
		return new ServerStyle(true, ServerStyle.MODE_GRADIENT, List.of(colors), 1.0f,
				false, false, ServerStyle.ALIGN_LEFT, false);
	}

	private static ServerStyle solid(String color, boolean bold) {
		return new ServerStyle(true, ServerStyle.MODE_SOLID, List.of(color), 1.0f,
				bold, false, ServerStyle.ALIGN_LEFT, false);
	}

	// ------------------------------------------------------------------
	// Name handling
	// ------------------------------------------------------------------

	/**
	 * Sanitises a user-typed preset name: strips control characters, quotes and
	 * backslashes (JSON-hostile), collapses runs of whitespace, trims, and
	 * truncates to {@link #MAX_NAME_LENGTH} code points.
	 *
	 * @return the cleaned name, or {@code ""} when nothing usable is left
	 */
	public static String sanitiseName(String input) {
		if (input == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(Math.min(input.length(), MAX_NAME_LENGTH));
		int count = 0;
		boolean lastWasSpace = false;
		for (int i = 0; i < input.length() && count < MAX_NAME_LENGTH; ) {
			int cp = input.codePointAt(i);
			int chars = Character.charCount(cp);
			boolean drop = Character.isISOControl(cp) || cp == '"' || cp == '\\' || cp == 0xA7;
			if (!drop) {
				boolean space = Character.isWhitespace(cp);
				if (!(space && lastWasSpace)) {
					sb.appendCodePoint(space ? ' ' : cp);
					count++;
				}
				lastWasSpace = space;
			}
			i += chars;
		}
		return sb.toString().trim();
	}

	/** Case-insensitive comparison key for a preset name. */
	private static String nameKey(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------
	// Queries
	// ------------------------------------------------------------------

	/** Defensive copy of the user's saved presets, in insertion order. */
	public synchronized List<Preset> userPresets() {
		List<Preset> copy = new ArrayList<>(userPresets.size());
		for (Preset p : userPresets) {
			copy.add(new Preset(p.name(), p.style().copy()));
		}
		return Collections.unmodifiableList(copy);
	}

	/** Number of user presets. */
	public synchronized int size() {
		return userPresets.size();
	}

	/** True when a user preset with this name already exists (case-insensitive). */
	public synchronized boolean hasUserPreset(String name) {
		String key = nameKey(name);
		if (key.isEmpty()) {
			return false;
		}
		for (Preset p : userPresets) {
			if (nameKey(p.name()).equals(key)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Saves {@code style} under {@code name}, replacing an existing preset with the
	 * same name (case-insensitive) instead of creating a duplicate.
	 *
	 * @return the stored name, or {@code null} when the name was unusable or the
	 *         store is already full
	 */
	public synchronized String save(String name, ServerStyle style) {
		String clean = sanitiseName(name);
		if (clean.isEmpty() || style == null) {
			return null;
		}
		String key = nameKey(clean);
		for (int i = 0; i < userPresets.size(); i++) {
			if (nameKey(userPresets.get(i).name()).equals(key)) {
				userPresets.set(i, new Preset(clean, style.copy()));
				markDirty();
				return clean;
			}
		}
		if (userPresets.size() >= MAX_PRESETS) {
			LOGGER.warn("Refusing to store more than {} presets.", MAX_PRESETS);
			return null;
		}
		userPresets.add(new Preset(clean, style.copy()));
		markDirty();
		return clean;
	}

	/** Deletes the user preset with this name. Returns true when something was removed. */
	public synchronized boolean delete(String name) {
		String key = nameKey(name);
		if (key.isEmpty()) {
			return false;
		}
		for (int i = 0; i < userPresets.size(); i++) {
			if (nameKey(userPresets.get(i).name()).equals(key)) {
				userPresets.remove(i);
				markDirty();
				return true;
			}
		}
		return false;
	}

	/**
	 * Renames an existing user preset, keeping its style and its position in the
	 * list.
	 *
	 * <p>Renaming onto a name that already belongs to a <em>different</em> preset
	 * is refused rather than silently merging two styles: the caller can then tell
	 * the user the name is taken. Renaming to the same name (differing only in
	 * case or surrounding whitespace) is allowed and simply adopts the new
	 * spelling.</p>
	 *
	 * @return the stored name, or {@code null} when the old name was not found,
	 *         the new name was unusable, or the new name is already taken
	 */
	public synchronized String rename(String oldName, String newName) {
		String oldKey = nameKey(oldName);
		if (oldKey.isEmpty()) {
			return null;
		}
		String clean = sanitiseName(newName);
		if (clean.isEmpty()) {
			return null;
		}
		int index = -1;
		for (int i = 0; i < userPresets.size(); i++) {
			if (nameKey(userPresets.get(i).name()).equals(oldKey)) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			return null;
		}
		String newKey = nameKey(clean);
		for (int i = 0; i < userPresets.size(); i++) {
			if (i != index && nameKey(userPresets.get(i).name()).equals(newKey)) {
				return null;
			}
		}
		Preset existing = userPresets.get(index);
		userPresets.set(index, new Preset(clean, existing.style().copy()));
		markDirty();
		return clean;
	}

	/**
	 * Copies an existing user preset under a new name, appending it to the end of
	 * the list.
	 *
	 * <p>Used by "save as new": the player wants a variant of a saved style
	 * without losing the original. The name must be free; duplicating onto an
	 * existing name is refused so a duplicate can never silently overwrite a
	 * different style.</p>
	 *
	 * @return the stored name, or {@code null} when the source was not found, the
	 *         new name was unusable, the name is taken, or the store is full
	 */
	public synchronized String duplicate(String sourceName, String newName) {
		String sourceKey = nameKey(sourceName);
		if (sourceKey.isEmpty()) {
			return null;
		}
		String clean = sanitiseName(newName);
		if (clean.isEmpty()) {
			return null;
		}
		ServerStyle source = null;
		for (Preset p : userPresets) {
			if (nameKey(p.name()).equals(sourceKey)) {
				source = p.style();
				break;
			}
		}
		if (source == null) {
			return null;
		}
		String newKey = nameKey(clean);
		for (Preset p : userPresets) {
			if (nameKey(p.name()).equals(newKey)) {
				return null;
			}
		}
		if (userPresets.size() >= MAX_PRESETS) {
			LOGGER.warn("Refusing to store more than {} presets.", MAX_PRESETS);
			return null;
		}
		userPresets.add(new Preset(clean, source.copy()));
		markDirty();
		return clean;
	}

	/**
	 * Suggests a free name derived from {@code base}, e.g. {@code "Sunset 2"}.
	 *
	 * <p>Used to pre-fill the rename/duplicate field so the player does not have to
	 * invent a name from scratch, and so a duplicate never collides by accident.</p>
	 */
	public synchronized String suggestName(String base) {
		String clean = sanitiseName(base);
		if (clean.isEmpty()) {
			clean = "Preset";
		}
		if (!hasUserPreset(clean)) {
			return clean;
		}
		for (int n = 2; n < MAX_PRESETS + 2; n++) {
			String candidate = sanitiseName(clean + " " + n);
			if (!candidate.isEmpty() && !hasUserPreset(candidate)) {
				return candidate;
			}
		}
		return clean;
	}

	private void markDirty() {
		dirty = true;
	}

	// ------------------------------------------------------------------
	// Load / save
	// ------------------------------------------------------------------

	/** Loads the preset file. Safe to call once at client init. */
	public synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Files.createDirectories(configDir);
		} catch (IOException e) {
			LOGGER.error("Could not create the ServerStyler config directory: {}", e.getMessage());
			return;
		}
		if (!Files.exists(configPath)) {
			// Nothing to load; the file is created on the first save.
			return;
		}
		if (!tryParse()) {
			recoverCorrupt();
		}
	}

	private boolean tryParse() {
		try {
			long size = Files.size(configPath);
			if (size > MAX_FILE_BYTES) {
				LOGGER.warn("{} is {} bytes, above the {} byte cap; treating it as corrupt.",
						FILE_NAME, size, MAX_FILE_BYTES);
				return false;
			}
		} catch (IOException e) {
			LOGGER.warn("Could not measure {}: {}", FILE_NAME, e.getMessage());
			return false;
		}

		try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[4096];
			long total = 0L;
			int read;
			while ((read = r.read(buffer)) != -1) {
				total += read;
				if (total > MAX_FILE_BYTES) {
					LOGGER.warn("{} grew past the {} byte cap while being read; "
							+ "treating it as corrupt.", FILE_NAME, MAX_FILE_BYTES);
					return false;
				}
				text.append(buffer, 0, read);
			}

			if (!JsonUtils.withinDepth(text.toString(), MAX_JSON_DEPTH)) {
				LOGGER.warn("{} nests deeper than {} levels or has unbalanced brackets; "
						+ "treating it as corrupt.", FILE_NAME, MAX_JSON_DEPTH);
				return false;
			}

			JsonElement rootEl = JsonParser.parseString(text.toString());
			if (!(rootEl instanceof JsonObject root)) {
				LOGGER.warn("{} is not a JSON object; treating it as corrupt.", FILE_NAME);
				return false;
			}

			JsonArray arr = JsonUtils.asArray(root.get("presets"), null);
			userPresets.clear();
			if (arr != null) {
				int limit = JsonUtils.limit(arr);
				for (int i = 0; i < limit && userPresets.size() < MAX_PRESETS; i++) {
					JsonElement el = arr.get(i);
					if (!(el instanceof JsonObject obj)) {
						continue;
					}
					String name = sanitiseName(JsonUtils.asString(obj.get("name"), "", MAX_NAME_LENGTH));
					if (name.isEmpty()) {
						continue;
					}
					JsonObject styleObj = JsonUtils.asObject(obj.get("style"), null);
					if (styleObj == null) {
						continue;
					}
					userPresets.add(new Preset(name, ServerStyleConfig.styleFromJson(styleObj)));
				}
			}
			LOGGER.info("Loaded {} saved preset(s).", userPresets.size());
			return true;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to parse {}: {}", FILE_NAME, e.getMessage());
			return false;
		}
	}

	private void recoverCorrupt() {
		Path backup = configPath.resolveSibling(FILE_NAME + BAK_SUFFIX);
		try {
			Files.move(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("ServerStyler recovered a corrupted {}: moved it to {} and will "
					+ "start with no saved presets.", FILE_NAME, backup.getFileName());
		} catch (IOException e) {
			LOGGER.error("Could not back up corrupted {}; leaving it in place: {}",
					FILE_NAME, e.getMessage());
		}
	}

	/** Queues an async atomic write when something changed. */
	public void saveAsync() {
		final JsonObject snapshot;
		synchronized (this) {
			if (!dirty) {
				return;
			}
			dirty = false;
			snapshot = snapshotLocked();
		}
		CompletableFuture.runAsync(() -> writeBlocking(snapshot), ioExecutor);
	}

	/** Writes synchronously. Used on shutdown and by tests. */
	public void saveNow() {
		final JsonObject snapshot;
		synchronized (this) {
			dirty = false;
			snapshot = snapshotLocked();
		}
		writeBlocking(snapshot);
	}

	/** Writes only when something changed since the last dispatch. */
	public void flushIfDirty() {
		final JsonObject snapshot;
		synchronized (this) {
			if (!dirty) {
				return;
			}
			dirty = false;
			snapshot = snapshotLocked();
		}
		writeBlocking(snapshot);
	}

	private JsonObject snapshotLocked() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		JsonArray arr = new JsonArray();
		for (Preset p : userPresets) {
			JsonObject o = new JsonObject();
			o.addProperty("name", p.name());
			o.add("style", ServerStyleConfig.styleToJson(p.style()));
			arr.add(o);
		}
		root.add("presets", arr);
		return root;
	}

	private void writeBlocking(JsonObject root) {
		Path tmp = configPath.resolveSibling(FILE_NAME + TMP_SUFFIX);
		try {
			Files.createDirectories(configDir);
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				gson.toJson(root, w);
				w.flush();
			}
			atomicReplace(tmp, configPath);
			LOGGER.debug("Saved presets to {}", FILE_NAME);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to save {}: {}", FILE_NAME, e.getMessage());
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
}
