package pl.zelekdev.serverstyler.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Defensive helpers for reading values from {@link JsonElement} graphs without
 * throwing, mirroring the tolerant-parsing philosophy of the config.
 *
 * <p>Because the config file is user-written and ships inside a distributable mod,
 * every reader here is bounded: a hostile or corrupted file can never make this
 * class allocate unbounded memory or recurse without a ceiling.</p>
 */
final class JsonUtils {

	/**
	 * Hard cap on how many entries are read from a single JSON array.
	 *
	 * <p>The largest legitimate array in this schema is the gradient colour list
	 * ({@link ServerStyle#MAX_GRADIENT_STOPS}). Anything far beyond that is either
	 * corruption or a deliberate attempt to exhaust memory, so parsing stops, but
	 * the file is still usable: the leading entries are read and the rest ignored.</p>
	 */
	static final int MAX_ARRAY_ENTRIES = 1024;

	private JsonUtils() {
	}

	static JsonObject asObject(JsonElement el, JsonObject fallback) {
		return el instanceof JsonObject o ? o : fallback;
	}

	static JsonArray asArray(JsonElement el, JsonArray fallback) {
		return el instanceof JsonArray a ? a : fallback;
	}

	/**
	 * Upper bound on how many elements of {@code array} will be visited.
	 *
	 * <p>Callers loop {@code for (int i = 0; i < limit(array); i++)} instead of the
	 * enhanced for, so a million-entry array costs at most {@link #MAX_ARRAY_ENTRIES}
	 * iterations.</p>
	 */
	static int limit(JsonArray array) {
		if (array == null) {
			return 0;
		}
		return Math.min(array.size(), MAX_ARRAY_ENTRIES);
	}

	static String asString(JsonElement el, String fallback) {
		if (el instanceof JsonPrimitive p && p.isString()) {
			return p.getAsString();
		}
		return fallback;
	}

	/**
	 * Reads a string, never returning more than {@code maxLength} characters.
	 *
	 * <p>Used for any value that ends up in a fixed-size field, so a hand-edited
	 * file cannot smuggle a multi-megabyte string into the model.</p>
	 */
	static String asString(JsonElement el, String fallback, int maxLength) {
		String value = asString(el, fallback);
		if (value == null) {
			return null;
		}
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	static boolean asBool(JsonElement el, boolean fallback) {
		if (el instanceof JsonPrimitive p && p.isBoolean()) {
			return p.getAsBoolean();
		}
		return fallback;
	}

	static int asInt(JsonElement el, int fallback) {
		if (el instanceof JsonPrimitive p && p.isNumber()) {
			int v;
			try {
				v = p.getAsInt();
			} catch (NumberFormatException e) {
				return fallback;
			}
			return v;
		}
		return fallback;
	}

	/**
	 * Reads a float, rejecting {@code NaN} and infinities.
	 *
	 * <p>Gson happily parses {@code 1e999} into {@code Infinity} and accepts the
	 * {@code NaN} literal that some JSON writers emit, either of which would poison
	 * the interpolator downstream. Such values fall back instead.</p>
	 */
	static float asFloat(JsonElement el, float fallback) {
		if (el instanceof JsonPrimitive p && p.isNumber()) {
			try {
				float v = p.getAsFloat();
				if (Float.isNaN(v) || Float.isInfinite(v)) {
					return fallback;
				}
				return v;
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
		return fallback;
	}

	/**
	 * Cheap pre-parse structural check: verifies {@code text} never nests brackets
	 * deeper than {@code maxDepth}.
	 *
	 * <p>Gson's parser is recursive, so a file consisting of a million {@code [}
	 * characters would overflow the stack (a {@link StackOverflowError}, which no
	 * {@code catch (Exception)} can recover from). Scanning the characters first
	 * turns that crash into an ordinary "corrupt config" path.</p>
	 *
	 * <p>Brackets inside string literals are ignored, and backslash escapes inside
	 * strings are honoured, so a legitimate value like {@code "a[b"} is not
	 * miscounted.</p>
	 */
	static boolean withinDepth(String text, int maxDepth) {
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
}