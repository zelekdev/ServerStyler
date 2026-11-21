package pl.zelekdev.serverstyler.i18n;

/**
 * Static access point for translations.
 *
 * <p>The GUI classes are constructed by mixins deep inside vanilla screens, so
 * threading a {@link LangManager} through every constructor would mean touching
 * a dozen signatures for no benefit. A single process-wide instance is set once
 * at client init and read from everywhere.</p>
 *
 * <p>Every method is null-safe: before {@link #install(LangManager)} runs (or in
 * a unit test that never installs one) lookups return the key itself rather than
 * throwing, so a GUI built during an early init phase still renders.</p>
 */
public final class I18n {

	private static volatile LangManager manager;

	private I18n() {
	}

	/** Installs the process-wide manager. Called once from the client entrypoint. */
	public static void install(LangManager langManager) {
		manager = langManager;
	}

	/** The installed manager, or {@code null} before init. */
	public static LangManager manager() {
		return manager;
	}

	/** Translates {@code key} in the active language. */
	public static String tr(String key) {
		LangManager m = manager;
		return m == null ? key : m.tr(key);
	}

	/** Translates {@code key} and substitutes {@code args}. */
	public static String tr(String key, Object... args) {
		LangManager m = manager;
		return m == null ? key : m.tr(key, args);
	}

	/** The active language, or English before init. */
	public static Lang lang() {
		LangManager m = manager;
		return m == null ? Lang.EN : m.current();
	}

	/** Switches the active language and persists the choice. */
	public static void setLang(Lang lang) {
		LangManager m = manager;
		if (m != null) {
			m.setCurrent(lang);
		}
	}
}
