package pl.zelekdev.serverstyler.render;

import pl.zelekdev.serverstyler.config.ConfigManager;
import pl.zelekdev.serverstyler.config.ServerStyle;
import pl.zelekdev.serverstyler.config.ServerStyleConfig;

import net.minecraft.network.chat.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache of styled server-name components keyed by {@code (ip, text)}.
 *
 * <p>The {@link ConfigManager#mutationCounter() config revision} acts as a cheap
 * dirty flag: whenever a style is edited or a config is reloaded the revision
 * changes and the whole cache is invalidated once. After that, every render
 * frame is a pure hash-map lookup &mdash; zero gradient interpolation cost while
 * the player is not actively editing.</p>
 */
public final class RenderCache {

	private static final int MAX_ENTRIES = 512;

	private record Key(String ip, String text) {
	}

	private final ConfigManager config;
	private final ConcurrentMap<Key, Component> cache = new ConcurrentHashMap<>();

	/** Last mutation revision the cache was validated against. */
	private long validatedRevision = Long.MIN_VALUE;

	public RenderCache(ConfigManager config) {
		this.config = config;
	}

	public ConfigManager config() {
		return config;
	}

	/**
	 * Returns the styled component to render for {@code text} for the server at
	 * {@code serverIp}. If the server has no (enabled) style, the plain literal
	 * component is returned so vanilla behaviour is preserved exactly.
	 */
	public Component styled(String serverIp, String text) {
		// Fail open, never closed: a caller that lost its config or its address must
		// still see the plain name rather than a blank row or a thrown exception
		// from inside a render callback.
		if (config == null) {
			return text == null ? Component.empty() : Component.literal(text);
		}
		long revision = config.mutationCounter();
		if (revision != validatedRevision) {
			cache.clear();
			validatedRevision = revision;
		}
		if (text == null || text.isEmpty()) {
			return Component.empty();
		}
		// A null address is legitimate (an entry that is not yet resolved); it just
		// never matches a styled key, because normaliseIp(null) is "" and no config
		// entry can be keyed on an empty string.
		Key key = new Key(ServerStyleConfig.normaliseIp(serverIp), text);
		Component cached = cache.get(key);
		if (cached != null) {
			return cached;
		}

		ServerStyle style = config.getEffective(serverIp);
		Component built = style == null
				? Component.literal(text)
				: TextStylingEngine.buildComponent(text, style);
		cache.put(key, built);
		trim();
		return built;
	}

	/** True if the given server currently has a rendered custom style. */
	public boolean isStyled(String serverIp) {
		return config != null && config.isCustomised(serverIp);
	}

	/** Drops every cached entry (used after a bulk config change). */
	public void invalidate() {
		cache.clear();
		if (config != null) {
			validatedRevision = config.mutationCounter();
		}
	}

	/**
	 * Keeps the cache bounded. A {@link ConcurrentHashMap} iterator is weakly
	 * consistent, which is fine here: this only ever needs to drop <em>some</em>
	 * entries, and a concurrent insert during the sweep just means the next call
	 * trims again.
	 */
	private void trim() {
		if (cache.size() <= MAX_ENTRIES) {
			return;
		}
		int drop = cache.size() - MAX_ENTRIES;
		var it = cache.entrySet().iterator();
		while (it.hasNext() && drop-- > 0) {
			it.next();
			it.remove();
		}
	}
}
