package pl.zelekdev.serverstyler.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ordered, idempotent up-migrations for the on-disk translation files.
 *
 * <p>Same philosophy as {@code SchemaMigration} for {@code servers.json}: the
 * steps edit the raw {@link JsonObject} so a hand-edited file keeps every value
 * the player changed, and only the structure is repaired.</p>
 *
 * <h3>Why a translation file needs migrating at all</h3>
 * <p>The files live in the config directory so players can reword them. That
 * means a file written by an older build is missing every key added since. The
 * migration therefore does two things:</p>
 * <ol>
 *   <li>Bumps {@code schemaVersion} to {@link #LATEST}.</li>
 *   <li>Adds every key from the bundled defaults that the file does not define,
 *       <b>without touching keys it already has</b>. This is what makes the
 *       files "update themselves when the schema updates" while preserving
 *       player edits.</li>
 * </ol>
 *
 * <h3>Contract for a step</h3>
 * <ul>
 *   <li><b>Never throws.</b> A malformed entry is repaired, not rejected.</li>
 *   <li><b>Idempotent.</b> Running twice produces the same bytes.</li>
 *   <li><b>Additive.</b> Existing values always win.</li>
 * </ul>
 */
public final class LangMigration {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/LangMigration");

	/** Oldest translation-file structure this build can read. */
	public static final int OLDEST_SUPPORTED = 1;

	/** Structure version written by this build. */
	public static final int LATEST = 2;

	/** A single structural upgrade applied in place to the JSON root. */
	@FunctionalInterface
	public interface Step {
		void apply(JsonObject root, Map<String, String> defaults);
	}

	private record Migration(int from, int to, String description, Step step) {
	}

	private static final List<Migration> STEPS = List.of(
			new Migration(1, 2, "added the strings object and language metadata",
					LangMigration::oneToTwo));

	private LangMigration() {
	}

	// ------------------------------------------------------------------
	// Version inspection
	// ------------------------------------------------------------------

	/**
	 * Reads {@code schemaVersion}, tolerating every kind of damage: a missing key,
	 * a string instead of a number, {@code null}, or an absurd value. Anything
	 * unreadable is treated as the oldest supported version so it gets migrated.
	 */
	public static int readVersion(JsonObject root) {
		if (root == null) {
			return OLDEST_SUPPORTED;
		}
		JsonElement el = root.get("schemaVersion");
		if (el instanceof JsonPrimitive p && p.isNumber()) {
			try {
				return Math.max(OLDEST_SUPPORTED, p.getAsInt());
			} catch (NumberFormatException e) {
				return OLDEST_SUPPORTED;
			}
		}
		return OLDEST_SUPPORTED;
	}

	/** True when the file predates this build and needs upgrading. */
	public static boolean needsMigration(int version) {
		return version < LATEST;
	}

	/** True when the file was written by a newer build than this one. */
	public static boolean isNewerThanBuild(int version) {
		return version > LATEST;
	}

	// ------------------------------------------------------------------
	// Driver
	// ------------------------------------------------------------------

	/**
	 * Upgrades {@code root} in place to {@link #LATEST} and back-fills every key
	 * from {@code defaults} that the file does not define.
	 *
	 * @return a human-readable description of what changed, or {@code ""} when the
	 *         file was already current and complete. The caller uses this to decide
	 *         whether the file has to be rewritten.
	 */
	public static String migrate(JsonObject root, Map<String, String> defaults) {
		if (root == null) {
			return "";
		}
		int version = readVersion(root);
		StringBuilder log = new StringBuilder();

		if (needsMigration(version)) {
			for (Migration m : STEPS) {
				if (m.from() < version) {
					// Already applied by an earlier run; keep going from here.
					continue;
				}
				try {
					m.step().apply(root, defaults);
				} catch (RuntimeException e) {
					// A broken entry must not abort the whole upgrade.
					LOGGER.warn("Translation migration v{}->v{} skipped a malformed section: {}",
							m.from(), m.to(), e.getMessage());
				}
				log.append("v").append(m.from()).append("->v").append(m.to())
						.append(" (").append(m.description()).append(") ");
			}
			root.addProperty("schemaVersion", LATEST);
		}

		// Runs on every load, not just on a version bump: this is what makes a
		// translation file pick up keys added by a newer build even when the
		// structure itself did not change.
		int added = ensureKeys(root, defaults);
		if (added > 0) {
			log.append("added ").append(added).append(" missing key(s) ");
		}
		return log.toString().trim();
	}

	// ------------------------------------------------------------------
	// v1 -> v2
	// ------------------------------------------------------------------

	/**
	 * v1 stored the strings at the root level; v2 nests them under
	 * {@code "strings"} so metadata ({@code schemaVersion}, {@code language}) can
	 * live beside them without colliding with a translation key.
	 */
	private static void oneToTwo(JsonObject root, Map<String, String> defaults) {
		JsonObject strings = new JsonObject();
		// Carry over any root-level string member that is not metadata.
		List<String> moved = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
			String key = entry.getKey();
			if (isMetadataKey(key)) {
				continue;
			}
			if (entry.getValue() instanceof JsonPrimitive p && p.isString()) {
				moved.add(key);
			}
		}
		for (String key : moved) {
			strings.add(key, root.get(key));
			root.remove(key);
		}
		root.add("strings", strings);
		ensureKeys(root, defaults);
	}

	// ------------------------------------------------------------------
	// Shared repair helpers
	// ------------------------------------------------------------------

	/** Keys that belong to the file envelope rather than to the string table. */
	private static boolean isMetadataKey(String key) {
		return "schemaVersion".equals(key) || "language".equals(key) || "strings".equals(key);
	}

	/**
	 * Adds every key from {@code defaults} that {@code root}'s string table does
	 * not already define, and drops non-string junk.
	 *
	 * @return how many keys were added
	 */
	private static int ensureKeys(JsonObject root, Map<String, String> defaults) {
		JsonObject strings = ensureStrings(root);
		int added = 0;

		// Drop members that are not strings: a hand-edited file could contain an
		// object or array where a translation belongs, which would break lookups.
		List<String> junk = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : strings.entrySet()) {
			if (!(entry.getValue() instanceof JsonPrimitive p) || !p.isString()) {
				junk.add(entry.getKey());
			}
		}
		for (String key : junk) {
			strings.remove(key);
		}

		if (defaults == null) {
			return 0;
		}
		for (Map.Entry<String, String> entry : defaults.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (key == null || value == null) {
				continue;
			}
			if (!strings.has(key)) {
				strings.addProperty(key, value);
				added++;
			}
		}
		return added;
	}

	private static JsonObject ensureStrings(JsonObject root) {
		JsonElement existing = root.get("strings");
		if (existing instanceof JsonObject o) {
			return o;
		}
		root.remove("strings");
		JsonObject created = new JsonObject();
		root.add("strings", created);
		return created;
	}
}
