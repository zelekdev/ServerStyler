package pl.zelekdev.serverstyler.config;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * One automatically assigned server id and the multiplayer entry it is bound to.
 *
 * <h3>Why ids exist</h3>
 * <p>Vanilla's {@code servers.dat} stores only a display name and an address per
 * entry, and {@code ServerData.write()} has no extension point, so a mod cannot
 * attach an id to a server entry. Styling used to be keyed on the address alone,
 * which forced two entries pointing at the same address to share one style. The
 * style map is now keyed by id, and the id is resolved from
 * {@code (address, name)} at render time.</p>
 *
 * <p>Ids are created automatically the first time a server is edited, so the
 * player never sees or types one. A legacy style that was keyed on a bare
 * address is bound to an <em>unnamed</em> identity ({@code name == null}), which
 * matches any name on that address until the server is edited and the binding
 * becomes explicit.</p>
 *
 * @param id   the generated key, {@code srv-<12 lowercase hex>}
 * @param ip   the normalised address this id belongs to
 * @param name the display name it was bound to, or {@code null} for a legacy
 *             address-only binding
 */
public record ServerIdentity(String id, String ip, String name) {

	/** Prefix that marks a config key as a server id rather than an address. */
	public static final String ID_PREFIX = "srv-";

	/** Number of hex characters after {@link #ID_PREFIX}. */
	public static final int ID_HEX_CHARS = 12;

	/** Total length of a generated id. */
	public static final int ID_LENGTH = ID_PREFIX.length() + ID_HEX_CHARS;

	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * True when {@code key} is a generated server id rather than an address.
	 *
	 * <p>Deliberately strict: only lowercase hex is accepted, so a hand-edited
	 * {@code SRV-...} key is normalised as an address instead of being mistaken
	 * for an id.</p>
	 */
	public static boolean isServerId(String key) {
		if (key == null || key.length() != ID_LENGTH || !key.startsWith(ID_PREFIX)) {
			return false;
		}
		for (int i = ID_PREFIX.length(); i < key.length(); i++) {
			char c = key.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	/** Generates a fresh id that is not in {@code taken}. */
	public static String generate(java.util.Set<String> taken) {
		byte[] bytes = new byte[ID_HEX_CHARS / 2];
		while (true) {
			RANDOM.nextBytes(bytes);
			StringBuilder sb = new StringBuilder(ID_LENGTH);
			sb.append(ID_PREFIX);
			for (byte b : bytes) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			String candidate = sb.toString();
			if (taken == null || !taken.contains(candidate)) {
				return candidate;
			}
		}
	}

	/**
	 * Trims a display name and caps it at the config's name budget.
	 *
	 * @return the trimmed name, or {@code null} when it is blank
	 */
	public static String normaliseName(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		return ServerStyleConfig.truncate(trimmed, ServerStyleConfig.MAX_NAME_LENGTH);
	}

	/** Lower-cased address used for every comparison. */
	public static String normaliseAddress(String ip) {
		return ServerStyleConfig.normaliseIp(ip).toLowerCase(Locale.ROOT);
	}
}
