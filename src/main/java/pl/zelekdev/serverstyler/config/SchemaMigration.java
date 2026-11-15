package pl.zelekdev.serverstyler.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ordered, idempotent up-migrations for {@code servers.json}.
 *
 * <h3>Why this operates on raw JSON and not on {@link ServerStyleConfig}</h3>
 * <p>A migration must be able to change the <em>structure</em> of the file &mdash;
 * rename keys, change a value's type (a lone colour string becoming an array),
 * relocate a block &mdash; including for fields that the current build's parser does
 * not even know about. Parsing into the model first would throw away exactly the
 * data we are trying to carry forward. Each step therefore receives the mutable
 * {@link JsonObject} root and edits it in place, and only afterwards is the result
 * handed to the Gson adapter.</p>
 *
 * <h3>Contract for a step</h3>
 * <ul>
 *   <li><b>Never throws.</b> A step must repair garbage rather than reject the file:
 *       the whole point is recovering from hand-edited or half-written configs.</li>
 *   <li><b>Idempotent.</b> Running a step twice must produce the same bytes as
 *       running it once. This makes a partially-applied migration safe to retry.</li>
 *   <li><b>Additive.</b> Values that are already valid for the newer schema are kept
 *       untouched, so a file written by a beta build is not flattened back to
 *       defaults.</li>
 * </ul>
 *
 * <h3>Version history</h3>
 * <pre>
 * v1  initial: enabled, mode, colors, opacity, bold, italic
 * v2  + alignment (LEFT | CENTER | RIGHT), colors always a JSON array
 * v3  + miniCaps (Unicode small capitals)
 * </pre>
 */
public final class SchemaMigration {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/Migration");

	/** Oldest structure this build can still read and upgrade. */
	public static final int OLDEST_SUPPORTED = 1;

	/** Structure version written by this build. */
	public static final int LATEST = ServerStyleConfig.SCHEMA_VERSION;

	/** A single structural upgrade applied in place to the JSON root. */
	@FunctionalInterface
	public interface Step {
		void apply(JsonObject root);
	}

	private record Migration(int from, int to, String description, Step step) {
	}

	private static final List<Migration> STEPS = List.of(
			new Migration(1, 2, "added alignment, normalised colors/mode/opacity",
					SchemaMigration::oneToTwo),
			new Migration(2, 3, "added miniCaps", SchemaMigration::twoToThree),
			new Migration(3, 4, "added gradient stop positions", SchemaMigration::threeToFour));

	private SchemaMigration() {
	}

	// ------------------------------------------------------------------
	// Version inspection
	// ------------------------------------------------------------------

	/**
	 * Reads {@code schemaVersion}, tolerating every kind of damage: a missing key, a
	 * string instead of a number, {@code null}, or an absurd value. Anything
	 * unreadable is treated as the oldest supported version so it gets migrated.
	 */
	public static int readVersion(JsonObject root) {
		if (root == null) {
			return OLDEST_SUPPORTED;
		}
		int raw = JsonUtils.asInt(root.get("schemaVersion"), OLDEST_SUPPORTED);
		return Math.max(OLDEST_SUPPORTED, raw);
	}

	/** True when the file predates this build and needs upgrading. */
	public static boolean needsMigration(int version) {
		return version < LATEST;
	}

	/**
	 * True when the file was written by a <em>newer</em> build than this one. Such a
	 * file is loaded read-tolerantly and its version is left alone; downgrading it
	 * would strip fields this build does not understand.
	 */
	public static boolean isNewerThanBuild(int version) {
		return version > LATEST;
	}

	// ------------------------------------------------------------------
	// Driver
	// ------------------------------------------------------------------

	/**
	 * Upgrades {@code root} in place to {@link #LATEST}.
	 *
	 * @return a human-readable description of every step that ran, or {@code ""}
	 *         when the file was already current. The caller logs this and uses it to
	 *         decide whether the file on disk has to be rewritten.
	 */
	public static String migrate(JsonObject root) {
		if (root == null) {
			return "";
		}
		int version = readVersion(root);
		if (!needsMigration(version)) {
			return "";
		}
		StringBuilder log = new StringBuilder();
		for (Migration m : STEPS) {
			if (m.from() < version) {
				// Already applied by an earlier run; keep going from here.
				continue;
			}
			try {
				m.step().apply(root);
			} catch (RuntimeException e) {
				// A broken entry must not abort the whole upgrade: skip it and let
				// the tolerant adapter supply defaults for whatever is missing.
				LOGGER.warn("Migration v{}->v{} skipped a malformed section: {}",
						m.from(), m.to(), e.getMessage());
			}
			log.append("v").append(m.from()).append("->v").append(m.to())
					.append(" (").append(m.description()).append(") ");
		}
		root.addProperty("schemaVersion", LATEST);
		return log.toString().trim();
	}

	// ------------------------------------------------------------------
	// v1 -> v2
	// ------------------------------------------------------------------

	private static void oneToTwo(JsonObject root) {
		JsonObject servers = ensureServers(root);
		for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
			if (!(entry.getValue() instanceof JsonObject style)) {
				continue;
			}
			String mode = normaliseMode(style.get("mode"));
			style.addProperty("mode", mode);
			style.add("colors", normaliseColors(style.get("colors"), mode));

			// v1 files were observed in the wild with opacity: 900.0.
			style.addProperty("opacity",
					ServerStyle.clampOpacity(JsonUtils.asFloat(style.get("opacity"), 1.0f)));
			style.addProperty("enabled", JsonUtils.asBool(style.get("enabled"), true));
			style.addProperty("bold", JsonUtils.asBool(style.get("bold"), false));
			style.addProperty("italic", JsonUtils.asBool(style.get("italic"), false));

			// The one genuinely new field of v2. A valid value from a beta build is
			// preserved; anything else collapses to LEFT.
			style.addProperty("alignment",
					ServerStyle.coerceAlignment(JsonUtils.asString(style.get("alignment"), null)));
		}
	}

	// ------------------------------------------------------------------
	// v2 -> v3
	// ------------------------------------------------------------------

	private static void twoToThree(JsonObject root) {
		JsonObject servers = ensureServers(root);
		for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
			if (!(entry.getValue() instanceof JsonObject style)) {
				continue;
			}
			// New in v3. Absent means "off", so a v2 file renders exactly as before.
			style.addProperty("miniCaps", JsonUtils.asBool(style.get("miniCaps"), false));
			// Defensive re-coercion: a hand-edited v2 file could carry junk here.
			if (style.has("alignment")) {
				style.addProperty("alignment",
						ServerStyle.coerceAlignment(JsonUtils.asString(style.get("alignment"), null)));
			}
		}
	}

	// ------------------------------------------------------------------
	// v3 -> v4
	// ------------------------------------------------------------------

	/**
	 * Adds evenly spaced gradient stop positions.
	 *
	 * <p>v4 stores a {@code "positions"} array parallel to {@code "colors"}, letting
	 * stops be dragged so one colour can occupy more of the name than another.
	 * Files from v3 (and earlier, already upgraded) have only ordered colours, so
	 * the correct back-fill is an even spread: {@code 0, 1/(n-1), ..., 1}. That
	 * reproduces exactly the gradient those files rendered before.</p>
	 */
	private static void threeToFour(JsonObject root) {
		JsonObject servers = ensureServers(root);
		for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
			if (!(entry.getValue() instanceof JsonObject style)) {
				continue;
			}
			JsonArray colors = JsonUtils.asArray(style.get("colors"), null);
			int count = colors == null ? 0 : colors.size();

			// Re-use an existing valid positions array (written by a later beta build)
			// rather than clobbering a user's custom spacing.
			JsonArray positions = normalisePositions(style.get("positions"), count);
			style.add("positions", positions);
			canonicaliseStyleOrder(style);
		}
	}

	/** Field order used when this build writes a style object. */
	private static final List<String> STYLE_FIELD_ORDER = List.of(
			"enabled", "mode", "colors", "positions", "opacity",
			"bold", "italic", "miniCaps", "alignment");

	/**
	 * Reorders a migrated style object into the canonical write order.
	 *
	 * <p>Migration steps run on raw JSON, so a key added by a step lands at the end
	 * of the object. The serializer, which always writes this build's own fields in
	 * a fixed order, would then rewrite the file on the next save even though no
	 * value changed &mdash; a diff-noise bug. Rebuilding the object in
	 * {@link #STYLE_FIELD_ORDER} makes the migrated file byte-identical to what the
	 * serializer emits, so an upgrade followed by a save is a no-op.</p>
	 *
	 * <p>Unknown keys are preserved after the known ones, so data written by a newer
	 * build survives.</p>
	 */
	private static void canonicaliseStyleOrder(JsonObject style) {
		JsonObject ordered = new JsonObject();
		for (String key : STYLE_FIELD_ORDER) {
			JsonElement value = style.get(key);
			if (value != null) {
				ordered.add(key, value);
			}
		}
		for (Map.Entry<String, JsonElement> entry : style.entrySet()) {
			if (!STYLE_FIELD_ORDER.contains(entry.getKey())) {
				ordered.add(entry.getKey(), entry.getValue());
			}
		}

		// JsonObject has no "replace in place", and the caller holds a reference to the
		// entry value inside a map we cannot re-seat from here. Copying the ordered
		// members back into the original instance (clear + re-add) keeps that
		// reference valid.
		style.entrySet().clear();
		for (Map.Entry<String, JsonElement> entry : ordered.entrySet()) {
			style.add(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Coerces a positions element into a canonical array of {@code count} floats:
	 * evenly spaced, strictly increasing, pinned to {@code 0} and {@code 1}.
	 */
	private static JsonArray normalisePositions(JsonElement el, int count) {
		JsonArray out = new JsonArray();
		if (count <= 0) {
			return out;
		}
		if (count == 1) {
			out.add(0.0f);
			return out;
		}

		float[] values = new float[count];
		boolean usable = el instanceof JsonArray arr && arr.size() == count;
		if (usable) {
			for (int i = 0; i < count; i++) {
				JsonElement e = ((JsonArray) el).get(i);
				values[i] = JsonUtils.asFloat(e, Float.NaN);
				if (Float.isNaN(values[i]) || Float.isInfinite(values[i])) {
					usable = false;
					break;
				}
			}
		}
		for (int i = 0; i < count; i++) {
			if (!usable) {
				values[i] = (float) i / (float) (count - 1);
			}
			values[i] = Math.max(0.0f, Math.min(1.0f, values[i]));
		}

		// Pin the ends and enforce a minimum gap between neighbours.
		values[0] = 0.0f;
		values[count - 1] = 1.0f;
		float minGap = ServerStyle.MIN_STOP_GAP;
		float maxGap = 1.0f / (float) (count - 1);
		if (minGap > maxGap) {
			minGap = maxGap * 0.5f;
		}
		for (int i = 1; i < count - 1; i++) {
			float lower = values[i - 1] + minGap;
			float upper = 1.0f - (float) (count - 1 - i) * minGap;
			values[i] = Math.max(lower, Math.min(upper, values[i]));
		}
		values[count - 1] = 1.0f;

		for (float v : values) {
			out.add(v);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Shared repair helpers
	// ------------------------------------------------------------------

	private static JsonObject ensureServers(JsonObject root) {
		JsonElement existing = root.get("servers");
		if (existing instanceof JsonObject o) {
			return o;
		}
		// Some v1 builds wrote the map at the root level without a wrapper.
		root.remove("servers");
		root.add("servers", new JsonObject());
		return new JsonObject();
	}

	private static String normaliseMode(JsonElement el) {
		String raw = JsonUtils.asString(el, ServerStyle.MODE_SOLID).trim().toUpperCase(Locale.ROOT);
		return ServerStyle.MODE_GRADIENT.equals(raw) ? ServerStyle.MODE_GRADIENT : ServerStyle.MODE_SOLID;
	}

	/**
	 * Coerces the colour list into a canonical array of {@code #RRGGBB} strings.
	 * Accepts the legacy single-string form, drops non-string junk, guarantees at
	 * least one stop, and collapses to one stop for SOLID.
	 */
	private static JsonArray normaliseColors(JsonElement el, String mode) {
		JsonArray out = new JsonArray();
		if (el instanceof JsonArray arr) {
			for (JsonElement stop : arr) {
				if (stop instanceof JsonPrimitive p && p.isString()) {
					out.add(ColorUtils.sanitiseHex(p.getAsString()));
				}
			}
		} else if (el instanceof JsonPrimitive p && p.isString()) {
			out.add(ColorUtils.sanitiseHex(p.getAsString()));
		}
		if (out.isEmpty()) {
			out.add('#' + ColorUtils.FALLBACK_HEX);
		}
		if (ServerStyle.MODE_SOLID.equals(mode)) {
			while (out.size() > 1) {
				out.remove(out.size() - 1);
			}
		} else if (out.size() > ServerStyle.MAX_GRADIENT_STOPS) {
			// Older builds allowed an unbounded stop count; the engine renders at
			// most MAX_GRADIENT_STOPS, so trim the tail rather than silently ignore it.
			while (out.size() > ServerStyle.MAX_GRADIENT_STOPS) {
				out.remove(out.size() - 1);
			}
		}
		return out;
	}
}