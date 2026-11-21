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

	/** Hard cap on how many id-to-server bindings are kept in memory. */
	public static final int MAX_IDENTITIES = 4096;

	private final Path configDir;
	private final Path configPath;
	private final Gson gson;

	private final Map<String, ServerStyle> styles = new LinkedHashMap<>();
	private final Map<String, ServerIdentity> identities = new LinkedHashMap<>();
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

	/**
	 * Interface language chosen by the player, stored in {@code servers.json} so it
	 * can be changed by hand when ModMenu is not installed.
	 */
	private volatile String language = ServerStyleConfig.DEFAULT_LANGUAGE;

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
		identities.clear();
		identities.putAll(cfg.getIdentities());
		this.language = cfg.getLanguage();
		mutationCounter.incrementAndGet();
		LOGGER.info("Loaded {} custom server style(s) and {} server id(s).",
				styles.size(), identities.size());
	}

	// ------------------------------------------------------------------
	// Interface language
	// ------------------------------------------------------------------

	/**
	 * The language code stored in the config ({@code en}, {@code pl} or {@code de}).
	 *
	 * <p>Kept in {@code servers.json} rather than in the language files themselves
	 * so a player without ModMenu can switch language by editing one line of the
	 * config.</p>
	 */
	public String getLanguage() {
		return language;
	}

	/**
	 * Stores a new language choice and schedules a save.
	 *
	 * @return the normalised code that was actually stored
	 */
	public synchronized String setLanguage(String code) {
		String normalised = pl.zelekdev.serverstyler.i18n.Lang.normalise(code);
		if (normalised.equals(this.language)) {
			return normalised;
		}
		this.language = normalised;
		markDirty();
		return normalised;
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

	/** Snapshot of every id-to-server binding. */
	public synchronized Map<String, ServerIdentity> getIdentities() {
		return Map.copyOf(new LinkedHashMap<>(identities));
	}

	// ------------------------------------------------------------------
	// Server ids
	// ------------------------------------------------------------------

	/**
	 * Read-only lookup of the id bound to {@code (ip, name)}, or {@code null}.
	 *
	 * <p>Never creates anything, so it is safe to call from a render pass. An
	 * unnamed legacy binding matches any name on that address, which is what keeps
	 * styles written before ids existed visible in the multiplayer list.</p>
	 */
	public synchronized String findId(String ip, String name) {
		String address = ServerIdentity.normaliseAddress(ip);
		if (address.isEmpty()) {
			return null;
		}
		String wanted = ServerIdentity.normaliseName(name);
		// 1. Exact binding.
		for (ServerIdentity identity : identities.values()) {
			if (identity.ip().equals(address)
					&& identity.name() != null
					&& identity.name().equals(wanted)) {
				return identity.id();
			}
		}
		// 2. Legacy address-only binding: adopt it for any name on that address.
		for (ServerIdentity identity : identities.values()) {
			if (identity.ip().equals(address) && identity.name() == null) {
				return identity.id();
			}
		}
		return null;
	}

	/**
	 * Returns the id bound to {@code (ip, name)}, creating one when needed.
	 *
	 * <p>Called from the editor screen only, never from a render pass: creating a
	 * binding marks the store dirty and schedules a write.</p>
	 *
	 * @return the id, or {@code null} when the address is unusable
	 */
	public synchronized String resolveId(String ip, String name) {
		String existing = findId(ip, name);
		if (existing != null) {
			// Promote a legacy address-only binding to an explicit one so the same
			// address can host a second, differently styled server from now on.
			ServerIdentity identity = identities.get(existing);
			String wanted = ServerIdentity.normaliseName(name);
			if (identity != null && identity.name() == null && wanted != null) {
				identities.put(existing, new ServerIdentity(existing, identity.ip(), wanted));
				markDirty();
			}
			return existing;
		}
		String address = ServerIdentity.normaliseAddress(ip);
		if (address.isEmpty() || !ServerStyleConfig.isPlausibleAddress(address)) {
			return null;
		}
		if (identities.size() >= MAX_IDENTITIES) {
			LOGGER.warn("Refusing to create another server id: {} bindings already exist.",
					identities.size());
			return null;
		}
		String id = ServerIdentity.generate(identities.keySet());
		identities.put(id, new ServerIdentity(id, address, ServerIdentity.normaliseName(name)));
		markDirty();
		LOGGER.info("Assigned server id {} to '{}'.", id, address);
		return id;
	}

	/**
	 * Rebinds {@code id} to a new address and/or name, keeping its style.
	 *
	 * <p>This is what makes "the user renamed the server address in the edit
	 * screen" behave sanely: the styling follows the server instead of being
	 * orphaned under the stale binding.</p>
	 */
	public synchronized void bindIdentity(String id, String ip, String name) {
		if (id == null) {
			return;
		}
		String address = ServerIdentity.normaliseAddress(ip);
		if (address.isEmpty() || !ServerStyleConfig.isPlausibleAddress(address)) {
			return;
		}
		ServerIdentity current = identities.get(id);
		ServerIdentity updated =
				new ServerIdentity(id, address, ServerIdentity.normaliseName(name));
		if (updated.equals(current)) {
			return;
		}
		identities.put(id, updated);
		markDirty();
	}

	/** Drops a binding and its style together, so no orphan is left behind. */
	public synchronized void removeIdentity(String id) {
		if (id == null) {
			return;
		}
		boolean changed = identities.remove(id) != null;
		changed |= styles.remove(id) != null;
		if (changed) {
			markDirty();
		}
	}

	// ------------------------------------------------------------------
	// Style accessors (keyed by server id, with a legacy address fallback)
	// ------------------------------------------------------------------

	/**
	 * Returns the style stored under {@code key} (a server id, or a legacy
	 * address), or {@code null} if none/enabled false.
	 */
	public synchronized ServerStyle getEffective(String key) {
		ServerStyle s = styles.get(ServerStyleConfig.normaliseKey(key));
		return (s != null && s.isEnabled()) ? s : null;
	}

	/** Returns the raw style (even when disabled), or {@code null} if none exists. */
	public synchronized ServerStyle getRaw(String key) {
		return styles.get(ServerStyleConfig.normaliseKey(key));
	}

	/**
	 * Resolves the effective style for a multiplayer entry.
	 *
	 * <p>Prefers the id bound to {@code (ip, name)}; falls back to a legacy
	 * address-keyed entry so a config that has not been re-saved yet still renders
	 * its styles.</p>
	 */
	public synchronized ServerStyle getEffectiveFor(String ip, String name) {
		String id = findId(ip, name);
		if (id != null) {
			ServerStyle s = styles.get(id);
			if (s != null) {
				return s.isEnabled() ? s : null;
			}
		}
		return getEffective(ip);
	}

	/** Raw counterpart of {@link #getEffectiveFor}, ignoring the enabled flag. */
	public synchronized ServerStyle getRawFor(String ip, String name) {
		String id = findId(ip, name);
		if (id != null) {
			ServerStyle s = styles.get(id);
			if (s != null) {
				return s;
			}
		}
		return getRaw(ip);
	}

	public synchronized boolean isCustomised(String ip, String name) {
		return getEffectiveFor(ip, name) != null;
	}

	public synchronized ServerStyle getOrCreate(String key) {
		String normalised = ServerStyleConfig.normaliseKey(key);
		if (!isAcceptableKey(normalised)) {
			// Never create an entry we would refuse to persist: an unstorable key would
			// silently vanish on the next save.
			return new ServerStyle();
		}
		ServerStyle existing = styles.get(normalised);
		if (existing != null) {
			return existing;
		}
		ServerStyle created = new ServerStyle();
		styles.put(normalised, created);
		markDirty();
		return created;
	}

	/**
	 * Stores {@code style} under {@code key} (a server id, or a legacy address).
	 *
	 * <p>The value is defensively copied so the caller (the live GUI model) can keep
	 * mutating its instance without corrupting the persisted state.</p>
	 */
	public synchronized void set(String key, ServerStyle style) {
		if (style == null) {
			return;
		}
		String normalised = ServerStyleConfig.normaliseKey(key);
		if (!isAcceptableKey(normalised)) {
			return;
		}
		styles.put(normalised, style.copy());
		markDirty();
	}

	/**
	 * Persists {@code style} under the id bound to {@code (ip, name)}, creating the
	 * binding when needed.
	 *
	 * @return the id the style was stored under, or {@code null} when the address
	 *         was unusable
	 */
	public synchronized String saveForServer(String ip, String name, ServerStyle style) {
		if (style == null) {
			return null;
		}
		String id = resolveId(ip, name);
		if (id == null) {
			LOGGER.warn("ServerStyler: cannot save a style without a valid server address.");
			return null;
		}
		styles.put(id, style.copy());
		markDirty();
		return id;
	}

	public synchronized void remove(String key) {
		if (styles.remove(ServerStyleConfig.normaliseKey(key)) != null) {
			markDirty();
		}
	}

	private void markDirty() {
		dirty = true;
		mutationCounter.incrementAndGet();
	}

	/**
	 * A config key must survive a JSON round-trip and stay within the address
	 * length budget. Delegates to {@link ServerStyleConfig#isPlausibleKey} so the
	 * same rules guard both the load path and the save path.
	 */
	private static boolean isAcceptableKey(String key) {
		return ServerStyleConfig.isPlausibleKey(key);
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
		ServerStyleConfig snapshot = new ServerStyleConfig(copy, identities);
		// The language lives outside the style map, so it has to be carried over
		// explicitly or every save would silently reset it to English.
		snapshot.setLanguage(this.language);
		return snapshot;
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
		ServerStyleConfig cfg = new ServerStyleConfig(new LinkedHashMap<>(), new LinkedHashMap<>());
		cfg.setLanguage(this.language);
		return cfg;
	}
}