package pl.zelekdev.serverstyler.i18n;

import java.util.Locale;

/**
 * The languages ServerStyler ships translations for.
 *
 * <p>The enum constant name is the stable identifier used in code; {@link #code()}
 * is what appears in {@code servers.json} ({@code "language": "pl"}) and in the
 * on-disk translation file names ({@code config/serverstyler/lang/pl.json}).</p>
 *
 * <p>Adding a language is a three-step change: add a constant here, drop a
 * bundled default at {@code assets/serverstyler/i18n/<code>.json}, and the
 * {@link LangManager} copies it out to the config directory on the next launch.</p>
 */
public enum Lang {

	/** English - the fallback for every missing key. */
	EN("en", "English"),

	/** Polish. */
	PL("pl", "Polski"),

	/** German. */
	DE("de", "Deutsch");

	/** Code written when no language has been chosen yet. */
	public static final String DEFAULT_CODE = "en";

	private final String code;
	private final String displayName;

	Lang(String code, String displayName) {
		this.code = code;
		this.displayName = displayName;
	}

	/** Two-letter code used in the config file and in translation file names. */
	public String code() {
		return code;
	}

	/** Name shown in the language picker, written in that language. */
	public String displayName() {
		return displayName;
	}

	/**
	 * Resolves a code from the config file.
	 *
	 * <p>Deliberately forgiving: the value is hand-editable, so {@code "PL"},
	 * {@code " pl "} and {@code "pl_PL"} all resolve to {@link #PL}, and anything
	 * unrecognised falls back to {@link #EN} rather than throwing.</p>
	 */
	public static Lang fromCode(String raw) {
		if (raw == null) {
			return EN;
		}
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return EN;
		}
		// Accept full locale tags such as "pl_pl" or "de-de".
		int cut = trimmed.length();
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c == '_' || c == '-' || c == '.') {
				cut = i;
				break;
			}
		}
		String base = trimmed.substring(0, cut);
		for (Lang lang : values()) {
			if (lang.code.equals(base)) {
				return lang;
			}
		}
		return EN;
	}

	/** Canonical code for a possibly-messy input, never {@code null}. */
	public static String normalise(String raw) {
		return fromCode(raw).code;
	}

	/** The next language in the picker, wrapping around. */
	public Lang next() {
		Lang[] all = values();
		return all[(ordinal() + 1) % all.length];
	}
}
