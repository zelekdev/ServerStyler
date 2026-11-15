package pl.zelekdev.serverstyler.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns persistence of {@code config/serverstyler/servers.json}.
 *
 * <ul>
 *   <li>Loads once at init, recovering from corrupt files (backup to {@code .bak}).</li>
 *   <li>Writes atomically: serialise to {@code servers.json.tmp}, then move over the
 *       real file. Happens off-thread via a single-thread executor so the render thread
 *       never blocks or stutters.</li>
 *   <li>Exposes a monotonically increasing {@link #mutationCounter()} that render caches
 *       can observe as a dirty flag.</li>
 * </ul>
 *
 * <h3>Concurrency contract</h3>
 * <p>All mutators and {@link #saveAsync()} are {@code synchronized} on the instance so
 * that the "dirty -> snapshot -> clear dirty" transition is atomic. Without this, a
 * {@code Done} click racing the IO thread could persist a stale snapshot or silently
 * drop a write &mdash; the classic "my settings did not save" failure mode.</p>
 *
 * <p>Writes are queued on a single daemon executor, therefore snapshots are applied to
 * disk strictly in the order they were taken. The last snapshot always wins.</p>
 */
public final class ConfigManager {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/Config");

	private static final String SUB_DIR = "serverstyler";
	private static final String FILE_NAME = "servers.json";
	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";

	/**
	 * Largest config file that will be read.
	 *
	 * <p>The schema holds at most a few hundred styles of a handful of bytes each,
	 * so a legitimate file is measured in kilobytes. A multi-megabyte
	 * {@code servers.json} can only be corruption or a denial-of-service attempt:
	 * reading it would allocate the whole thing (and the parsed tree) on the client
	 * thread during startup. Oversized files are therefore rejected and treated
	 * exactly like a corrupt file - they are moved aside, not loaded.</p>
	 */
	public static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;

	/**
	 * Maximum nesting depth accepted before parsing.
	 *
	 * <p>Gson parses recursively, so a file of nothing but {@code [} characters
	 * would overflow the stack with an unrecoverable {@link StackOverflowError}. A
	 * cheap bracket scan first turns that into a normal "corrupt config" path. The
	 * real schema nests four levels deep ({@code root -> servers -> style ->
	 * colors}), so 32 is generous.</p>
	 */
	public static final int MAX_JSON_DEPTH = 32;

	/**
	 * Minimum milliseconds between two async writes.
	 *
	 * <p>Every slider tick in the editor marks the model dirty; without a floor a
	 * user dragging a hue slider would queue hundreds of whole-file writes. The
	 * write is coalesced instead: the first save goes out immediately, then at most
	 * one write per interval follows, always carrying the newest snapshot.</p>
	 */
	private static final long MIN_SAVE_INTERVAL_MS = 250L;

	private final Path configDir;
	private final Path configPath;
	private final Gson gson;

	private final Map<String, ServerStyle> styles = new LinkedHashMap<>();
	private final AtomicLong mutationCounter = new AtomicLong(0);
	private final Executor ioExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ServerStyler-Io");
		t.setDaemon(true);
		return t;
	});

	private volatile boolean loaded = false;
	private volatile boolean dirty = false;

	/** {@link System#currentTimeMillis()} of the last write dispatched to IO. */
	private long lastSaveDispatchMs = 0L;

	/**
	 * True while a delayed (rate-limited) save is already queued, so a burst of
	 * mutations collapses into a single trailing write instead of hundreds.
	 */
	private boolean saveScheduled = false;
	/** Set by {@link #tryParse()} when an on-disk schema upgrade was applied. */
	private volatile boolean migrationPending = false;
	/** Schema version found on disk before any upgrade (for the backup name). */
	private volatile int lastDetectedVersion = SchemaMigration.LATEST;
	/** True once this session successfully upgraded the on-disk file. */
	private volatile boolean migratedOnLoad = false;
	/**
	 * Set when the file was written by a newer build. Saves are then suppressed so
	 * fields this build does not know about survive on disk instead of being
	 * stripped by our serializer.
	 */
	private volatile boolean diskReadOnly = false;

	/**
	 * The migrated JSON tree awaiting a write-back. Kept only between
	 * {@link #tryParse()} and the write in {@link #load()} so the upgrade is applied
	 * to the raw file without passing through the lossy model.
	 */
	private volatile JsonObject migratedRoot = null;

	public ConfigManager() {
		this(FabricLoader.getInstance().getConfigDir());
	}

	/** Test/alternative-location constructor. */
	public ConfigManager(Path baseConfigDir) {
		this.configDir = baseConfigDir.resolve(SUB_DIR);
		this.configPath = configDir.resolve(FILE_NAME);
		this.gson = ServerStyleConfig.createGson();
	}

	public Path getConfigPath() {
		return configPath;
	}

	// ------------------------------------------------------------------
	// Diagnostics (exposed for the in-game status command / tests)
	// ------------------------------------------------------------------

	/** Schema version this build writes. */
	public int schemaVersion() {
		return SchemaMigration.LATEST;
	}

	/** Schema version that was on disk when the file was last loaded. */
	public int detectedSchemaVersion() {
		return lastDetectedVersion;
	}

	/**
	 * True when the config on disk belongs to a newer build and is therefore being
	 * treated as read-only, so this build cannot silently strip its unknown fields.
	 */
	public boolean isReadOnly() {
		return diskReadOnly;
	}

	/** True when the on-disk file still needs a structural upgrade. */
	public synchronized boolean needsMigration() {
		// After a successful upgrade the file is already current, even though the
		// version reported by detectedSchemaVersion() still names what was found.
		return !migratedOnLoad && !diskReadOnly
				&& SchemaMigration.needsMigration(lastDetectedVersion);
	}

	/** True when this session upgraded the on-disk file (and made a backup). */
	public boolean wasMigrated() {
		return migratedOnLoad;
	}

	/** Loads (or regenerates) the config. Safe to call once at client init. */
	public synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Files.createDirectories(configDir);
		} catch (IOException e) {
			// Never log the absolute path: user-facing logs are shared in bug reports
			// and the path contains the account name.
			LOGGER.error("Could not create the ServerStyler config directory: {}", e.getMessage());
			return;
		}

		if (!Files.exists(configPath)) {
			LOGGER.info("No {} yet; creating a fresh config.", FILE_NAME);
			saveBlocking(buildDefault());
			return;
		}

		ServerStyleConfig parsed = tryParse();
		if (parsed == null) {
			recoverCorrupt();
			parsed = buildDefault();
			saveBlocking(parsed);
		} else if (migrationPending && migratedRoot != null) {
			// Persist the upgraded structure immediately, writing the RAW migrated JSON
			// rather than re-serialising the model: the model only knows this build's
			// fields, so going through it would silently delete anything unknown (for
			// example fields from a beta build). The pre-migration file is also kept
			// aside so nothing is unrecoverable.
			backupBeforeMigration();
			writeRaw(migratedRoot);
			migrationPending = false;
			migratedRoot = null;
			migratedOnLoad = true;
		}
		apply(parsed);
	}

	/**
	 * Copies the about-to-be-upgraded file to {@code servers.json.v<old>.bak} before
	 * it is rewritten, guarding against a migration bug losing user data.
	 */
	private void backupBeforeMigration() {
		Path backup = configPath.resolveSibling(
				FILE_NAME + ".v" + lastDetectedVersion + BAK_SUFFIX);
		try {
			Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.info("Backed up pre-migration config to {}", backup.getFileName());
		} catch (IOException e) {
			// Not fatal: the migration is additive and idempotent, so a failed backup
			// only costs the "undo" convenience.
			LOGGER.warn("Could not back up {} before migrating: {}",
					configPath.getFileName(), e.getMessage());
		}
	}

	private ServerStyleConfig tryParse() {
		try {
			long size = Files.size(configPath);
			if (size > MAX_FILE_BYTES) {
				LOGGER.warn("{} is {} bytes, above the {} byte cap; treating it as corrupt.",
						FILE_NAME, size, MAX_FILE_BYTES);
				return null;
			}
		} catch (IOException e) {
			LOGGER.warn("Could not measure {}: {}", FILE_NAME, e.getMessage());
			return null;
		}

		try (Reader r = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
			// Read into a string first so the depth guard can inspect the raw text
			// before Gson's recursive parser ever touches it.
			StringBuilder text = new StringBuilder();
			char[] buffer = new char[4096];
			long total = 0L;
			int read;
			while ((read = r.read(buffer)) != -1) {
				total += read;
				if (total > MAX_FILE_BYTES) {
					LOGGER.warn("{} grew past the {} byte cap while being read; "
							+ "treating it as corrupt.", FILE_NAME, MAX_FILE_BYTES);
					return null;
				}
				text.append(buffer, 0, read);
			}

			if (!JsonUtils.withinDepth(text.toString(), MAX_JSON_DEPTH)) {
				LOGGER.warn("{} nests deeper than {} levels or has unbalanced brackets; "
						+ "treating it as corrupt.", FILE_NAME, MAX_JSON_DEPTH);
				return null;
			}

			JsonElement rootEl = JsonParser.parseString(text.toString());
			JsonObject root = rootEl instanceof JsonObject o ? o : null;
			if (root == null) {
				LOGGER.warn("{} is not a JSON object; treating it as corrupt.", FILE_NAME);
				return null;
			}

			// Upgrade the structure BEFORE handing it to the model adapter: the steps
			// edit raw keys/types, which the typed adapter would silently discard.
			int detected = SchemaMigration.readVersion(root);
			lastDetectedVersion = detected;
			String applied = SchemaMigration.migrate(root);
			if (!applied.isEmpty()) {
				migrationPending = true;
				migratedRoot = root;
				LOGGER.info("Upgraded {} from schema v{}: {}", FILE_NAME, detected, applied);
			} else if (SchemaMigration.isNewerThanBuild(detected)) {
				// Written by a newer build: load what we understand, never downgrade.
				migrationPending = false;
				diskReadOnly = true;
				LOGGER.warn("{} uses schema v{}, newer than this build's v{}. Loading it "
								+ "read-only so fields this build does not know about are "
								+ "not overwritten.",
						FILE_NAME, detected, SchemaMigration.LATEST);
			}

			ServerStyleConfig cfg = gson.fromJson(root, ServerStyleConfig.class);
			if (cfg == null) {
				return null;
			}
			return cfg;
		} catch (JsonSyntaxException | IOException e) {
			LOGGER.warn("Failed to parse {}: {}", FILE_NAME, e.getMessage());
			return null;
		}
	}

	/**
	 * Moves a syntactically broken file aside to {@code servers.json.bak} (overwriting
	 * any prior backup), logs a clear warning, then a clean default is used.
	 */
	private void recoverCorrupt() {
		Path backup = configPath.resolveSibling(FILE_NAME + BAK_SUFFIX);
		try {
			Files.move(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
			LOGGER.warn("ServerStyler recovered a corrupted {}: moved it to {} and will "
					+ "write a fresh config.", FILE_NAME, backup.getFileName());
		} catch (IOException e) {
			// Only the file name: the absolute path identifies the OS account.
			LOGGER.error("Could not back up corrupted {}; leaving it in place: {}",
					FILE_NAME, e.getMessage());
		}
	}

	private void apply(ServerStyleConfig cfg) {
		styles.clear();
		styles.putAll(cfg.getServers());
		mutationCounter.incrementAndGet();
		LOGGER.info("Loaded {} custom server style(s).", styles.size());
	}

	// ------------------------------------------------------------------
	// Accessors (map + cached queries)
	// ------------------------------------------------------------------

	/**
	 * Snapshot of all stored styles.
	 *
	 * <p>Both the map and every value are copied: {@link ServerStyle} is mutable, so
	 * handing out the live instances would let a caller change the persisted model
	 * without going through {@link #set} - silently bypassing the write path.</p>
	 */
	public synchronized Map<String, ServerStyle> getAll() {
		Map<String, ServerStyle> copy = new LinkedHashMap<>(styles.size());
		for (Map.Entry<String, ServerStyle> e : styles.entrySet()) {
			copy.put(e.getKey(), e.getValue().copy());
		}
		return Map.copyOf(copy);
	}

	/** Returns the style for a normalised IP, or {@code null} if none/enabled false. */
	public synchronized ServerStyle getEffective(String serverIp) {
		ServerStyle s = styles.get(ServerStyleConfig.normaliseIp(serverIp));
		return (s != null && s.isEnabled()) ? s : null;
	}

	/** Returns the raw style (even when disabled), or {@code null} if none exists. */
	public synchronized ServerStyle getRaw(String serverIp) {
		return styles.get(ServerStyleConfig.normaliseIp(serverIp));
	}

	public synchronized boolean isCustomised(String serverIp) {
		ServerStyle s = getEffective(serverIp);
		return s != null;
	}

	public synchronized ServerStyle getOrCreate(String serverIp) {
		String key = ServerStyleConfig.normaliseIp(serverIp);
		if (!isAcceptableKey(key)) {
			// Never create an entry we would refuse to persist: an unstorable key would
			// silently vanish on the next save.
			ServerStyle throwaway = new ServerStyle();
			return throwaway;
		}
		ServerStyle existing = styles.get(key);
		if (existing != null) {
			return existing;
		}
		ServerStyle created = new ServerStyle();
		styles.put(key, created);
		markDirty();
		return created;
	}

	/**
	 * Stores {@code style} under the normalised {@code serverIp}.
	 *
	 * <p>The value is defensively copied so the caller (the live GUI model) can keep
	 * mutating its instance without corrupting the persisted state.</p>
	 */
	public synchronized void set(String serverIp, ServerStyle style) {
		if (style == null) {
			return;
		}
		String key = ServerStyleConfig.normaliseIp(serverIp);
		if (!isAcceptableKey(key)) {
			return;
		}
		styles.put(key, style.copy());
		markDirty();
	}

	/**
	 * Persists {@code style} under {@code newIp} while migrating away from
	 * {@code oldIp}. This is what makes "the user renamed the server address in the
	 * edit screen" behave sanely: the styling follows the server instead of being
	 * orphaned under the stale key.
	 *
	 * @param oldIp the address the editor was opened with (may be {@code null}/equal)
	 * @param newIp the address currently typed into the IP field
	 */
	public synchronized void saveForServer(String oldIp, String newIp, ServerStyle style) {
		if (style == null) {
			return;
		}
		String newKey = ServerStyleConfig.normaliseIp(newIp);
		if (!isAcceptableKey(newKey)) {
			LOGGER.warn("ServerStyler: cannot save a style without a valid server address.");
			return;
		}
		String oldKey = ServerStyleConfig.normaliseIp(oldIp);

		styles.put(newKey, style.copy());
		if (!oldKey.isEmpty() && !oldKey.equals(newKey)) {
			// IP was edited: drop the stale entry so we do not accumulate orphans.
			styles.remove(oldKey);
			LOGGER.info("ServerStyler: migrated style from '{}' to '{}'.", oldKey, newKey);
		}
		markDirty();
	}

	public synchronized void remove(String serverIp) {
		if (styles.remove(ServerStyleConfig.normaliseIp(serverIp)) != null) {
			markDirty();
		}
	}

	private void markDirty() {
		dirty = true;
		mutationCounter.incrementAndGet();
	}

	/**
	 * A config key must survive a JSON round-trip and stay within the address
	 * length budget. Delegates to {@link ServerStyleConfig#isPlausibleAddress} so
	 * the same rules guard both the load path and the save path.
	 */
	private static boolean isAcceptableKey(String key) {
		return ServerStyleConfig.isPlausibleAddress(key);
	}

	/** Monotonic revision used as a cheap dirty flag by caches. */
	public long mutationCounter() {
		return mutationCounter.get();
	}

	/**
	 * Flags that a style was mutated and schedules an async atomic save if any
	 * change occurred since the last write.
	 *
	 * <p>The snapshot is taken inside the monitor so a concurrent {@link #set} can
	 * never be lost between "we saw dirty" and "we copied the map".</p>
	 *
	 * <p>Writes are rate-limited: a user dragging a hue slider marks the model dirty
	 * on every tick, and without a floor that would queue hundreds of whole-file
	 * writes. The first save is dispatched immediately; a burst inside
	 * {@link #MIN_SAVE_INTERVAL_MS} collapses into exactly one trailing write that
	 * carries the newest snapshot.</p>
	 */
	public void saveAsync() {
		final ServerStyleConfig snapshot;
		synchronized (this) {
			if (!dirty) {
				return;
			}
			long now = System.currentTimeMillis();
			long sinceLast = now - lastSaveDispatchMs;
			if (sinceLast < MIN_SAVE_INTERVAL_MS) {
				// Too soon: leave the data dirty and let the already-scheduled trailing
				// write pick it up. This is what coalesces a slider drag into one save.
				scheduleTrailingSaveLocked(MIN_SAVE_INTERVAL_MS - sinceLast);
				return;
			}
			dirty = false;
			lastSaveDispatchMs = now;
			snapshot = snapshotLocked();
		}
		CompletableFuture.runAsync(() -> saveBlocking(snapshot), ioExecutor);
	}

	/**
	 * Queues a single delayed save. Called with the monitor held, and guarded by
	 * {@link #saveScheduled} so a burst of mutations cannot queue more than one.
	 */
	private void scheduleTrailingSaveLocked(long delayMs) {
		if (saveScheduled) {
			return;
		}
		saveScheduled = true;
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(Math.max(1L, delayMs));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			synchronized (ConfigManager.this) {
				saveScheduled = false;
			}
			// Re-enter the normal path: if still dirty it dispatches now, and if not
			// this is a cheap no-op.
			saveAsync();
		}, ioExecutor);
	}

	/**
	 * Synchronously writes the current state. Used on shutdown and by tests; the
	 * normal game path uses {@link #saveAsync()}.
	 */
	public void saveNow() {
		final ServerStyleConfig snapshot;
		synchronized (this) {
			dirty = false;
			snapshot = snapshotLocked();
		}
		saveBlocking(snapshot);
	}

	/**
	 * Writes only if something changed since the last successful dispatch.
	 *
	 * <p>Used by the shutdown hook so quitting a client that never touched a style
	 * does not rewrite {@code servers.json} on every exit (and cannot touch a file
	 * the player deliberately made read-only).</p>
	 */
	public void flushIfDirty() {
		final ServerStyleConfig snapshot;
		synchronized (this) {
			if (!dirty) {
				return;
			}
			dirty = false;
			snapshot = snapshotLocked();
		}
		saveBlocking(snapshot);
	}

	private ServerStyleConfig snapshotLocked() {
		Map<String, ServerStyle> copy = new LinkedHashMap<>();
		for (Map.Entry<String, ServerStyle> e : styles.entrySet()) {
			copy.put(e.getKey(), e.getValue().copy());
		}
		return new ServerStyleConfig(copy);
	}

	// ------------------------------------------------------------------
	// Atomic write
	// ------------------------------------------------------------------

	private void saveBlocking(ServerStyleConfig cfg) {
		if (diskReadOnly) {
			LOGGER.debug("Skipping save: {} was written by a newer ServerStyler build.",
					configPath.getFileName());
			return;
		}
		Path tmp = configPath.resolveSibling(FILE_NAME + TMP_SUFFIX);
		try {
			Files.createDirectories(configDir);
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				gson.toJson(cfg, w);
				w.flush();
			}
			atomicReplace(tmp, configPath);
			LOGGER.debug("Saved {} style(s) to {}", cfg.getServers().size(), FILE_NAME);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to save {}: {}", FILE_NAME, e.getMessage());
		}
	}

	/**
	 * Writes an already-built JSON tree atomically. Used for schema migrations, where
	 * the tree must reach disk verbatim: re-serialising the model would drop any
	 * field this build does not model.
	 */
	private void writeRaw(JsonObject root) {
		Path tmp = configPath.resolveSibling(FILE_NAME + TMP_SUFFIX);
		try {
			Files.createDirectories(configDir);
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				gson.toJson(root, w);
				w.flush();
			}
			atomicReplace(tmp, configPath);
			LOGGER.info("Wrote the migrated {} to disk.", FILE_NAME);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to write the migrated {}: {}", FILE_NAME, e.getMessage());
		}
	}

	private void atomicReplace(Path tmp, Path target) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// Filesystem lacking atomic move: do a plain replace.
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private ServerStyleConfig buildDefault() {
		return new ServerStyleConfig(new LinkedHashMap<>());
	}
}