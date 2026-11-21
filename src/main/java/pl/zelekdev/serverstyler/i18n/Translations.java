package pl.zelekdev.serverstyler.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable key &rarr; text table for one language.
 *
 * <p>Lookups never throw: a key missing from this table falls back to the
 * English table, and a key missing from both returns the key itself so a
 * missing translation is visible in-game instead of crashing the GUI.</p>
 */
public final class Translations {

	/** Maximum length accepted for a single translated string. */
	static final int MAX_VALUE_LENGTH = 512;

	private final Lang lang;
	private final Map<String, String> values;
	private final Translations fallback;

	Translations(Lang lang, Map<String, String> values, Translations fallback) {
		this.lang = lang;
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.fallback = fallback;
	}

	/** The language this table belongs to. */
	public Lang lang() {
		return lang;
	}

	/** Raw lookup without formatting; {@code null} when the key is unknown. */
	public String raw(String key) {
		if (key == null) {
			return null;
		}
		String value = values.get(key);
		if (value != null) {
			return value;
		}
		return fallback == null ? null : fallback.raw(key);
	}

	/**
	 * Looks up {@code key} and substitutes {@code args} with
	 * {@link String#format(String, Object...)}.
	 *
	 * <p>A malformed format string (a hand-edited file with a stray {@code %})
	 * must not crash the render thread, so a failed format returns the raw text
	 * with the placeholders left in place.</p>
	 */
	public String format(String key, Object... args) {
		String pattern = raw(key);
		if (pattern == null) {
			return key;
		}
		if (args == null || args.length == 0) {
			return pattern;
		}
		try {
			return String.format(pattern, args);
		} catch (RuntimeException e) {
			return pattern;
		}
	}

	/** Every key defined by this table (not including fallback keys). */
	public Map<String, String> entries() {
		return values;
	}

	/** True when this table defines {@code key} itself. */
	public boolean defines(String key) {
		return values.containsKey(key);
	}
}
