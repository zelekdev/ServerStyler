package pl.zelekdev.serverstyler.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gson adapter + model for the JSON config file (schema v3).
 *
 * <p>File layout:</p>
 * <pre>
 * {
 *   "schemaVersion": 6,
 *   "language": "en",
 *   "servers": {
 *     "srv-3f9a1c07b2de": {
 *       "enabled": true,
 *       "mode": "GRADIENT",
 *       "colors": ["#FF55AA", "#00AAFF"],
 *       "positions": [0.0, 1.0],
 *       "opacity": 1.0,
 *       "bold": false,
 *       "italic": false,
 *       "miniCaps": false,
 *       "alignment": "LEFT"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>{@code positions} is parallel to {@code colors}: the normalised {@code 0..1}
 * position of each stop along the name. The first is always {@code 0} and the last
 * always {@code 1}; only interior stops move, which is what allows one colour to
 * occupy more of the name than another (e.g. {@code [0.0, 0.2, 1.0]}).</p>
 *
 * <p>Since v6 the {@code servers} map is keyed by an automatically assigned
 * <em>server id</em> ({@code srv-<12 hex>}) instead of the address, so two
 * multiplayer entries pointing at the same address can carry different styles.
 * The id-to-address binding lives in {@code identities.json}; see
 * {@link ServerIdentityStore}. Files from v5 and earlier are keyed by address and
 * are converted by {@link SchemaMigration}.</p>
 *
 * <p>Older structures are upgraded before parsing by {@link SchemaMigration}, which
 * edits the raw JSON so it can reshape keys and value types (and so fields this
 * build does not model survive the upgrade).</p>
 *
 * <p>The deserializer is intentionally tolerant: unknown fields are ignored,
 * missing fields take defaults (so a v1 file without {@code alignment} loads as
 * {@code LEFT}), and malformed values fall back to safe defaults without ever
 * throwing.</p>
 */
public final class ServerStyleConfig {

	// ------------------------------------------------------------------
	// Length safeguards
	// ------------------------------------------------------------------

	/** Hard cap on a rendered server name, matching vanilla's {@code EditBox} limit. */
	public static final int MAX_NAME_LENGTH = 64;

	/** Maximum length accepted for a server address key. Vanilla uses 128. */
	public static final int MAX_ADDRESS_LENGTH = 128;

	/** Schema version written by this build. */
	public static final int SCHEMA_VERSION = 6;

	/** Language code written when none has been chosen. */
	public static final String DEFAULT_LANGUAGE = "en";

	private int schemaVersion = SCHEMA_VERSION;
	private String language = DEFAULT_LANGUAGE;
	private final Map<String, ServerStyle> servers;
	private final Map<String, ServerIdentity> identities;

	public ServerStyleConfig(Map<String, ServerStyle> servers) {
		this(servers, null);
	}

	public ServerStyleConfig(Map<String, ServerStyle> servers,
							 Map<String, ServerIdentity> identities) {
		this.schemaVersion = SCHEMA_VERSION;
		this.servers = servers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(servers);
		this.identities = identities == null
				? new LinkedHashMap<>()
				: new LinkedHashMap<>(identities);
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}

	/**
	 * The language code chosen by the player ({@code "en"}, {@code "pl"},
	 * {@code "de"}).
	 *
	 * <p>Stored in this file rather than in the translation files so a player
	 * without ModMenu can change it by editing one obvious line. Always a
	 * canonical code: {@link #setLanguage} normalises whatever it is given.</p>
	 */
	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = pl.zelekdev.serverstyler.i18n.Lang.normalise(language);
	}

	/** Live (mutable) map; callers must synchronise through the ConfigManager. */
	public Map<String, ServerStyle> getServers() {
		return servers;
	}

	/**
	 * Live (mutable) id-to-binding map; callers must synchronise through the
	 * {@link ConfigManager}.
	 *
	 * <p>Stored in the same file as the styles so the two can never drift apart:
	 * a style keyed on an id whose binding was lost would be unreachable, and a
	 * binding without a style is just a wasted line.</p>
	 */
	public Map<String, ServerIdentity> getIdentities() {
		return identities;
	}

	/** Normalises an IP/address key for the config map (trim + lowercase). */
	public static String normaliseIp(String ip) {
		if (ip == null) {
			return "";
		}
		return ip.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * Normalises a {@code servers} map key.
	 *
	 * <p>Since v6 a key is either a generated server id ({@code srv-<12 hex>},
	 * already canonical) or a legacy address. Ids are returned untouched; anything
	 * else is normalised as an address so a hand-edited file still matches.</p>
	 */
	public static String normaliseKey(String key) {
		if (key == null) {
			return "";
		}
		String trimmed = key.trim();
		if (ServerIdentity.isServerId(trimmed)) {
			return trimmed;
		}
		return normaliseIp(trimmed);
	}

	/**
	 * Structural plausibility check for a config key.
	 *
	 * <p>A key is the {@code servers} object member name, so it must survive a JSON
	 * round-trip and must not be able to smuggle anything unusual into the file:
	 * control characters (including newlines), the section sign, quotes and
	 * backslashes are all rejected. Length is bounded by
	 * {@link #MAX_ADDRESS_LENGTH}.</p>
	 *
	 * <p>This is deliberately <em>not</em> a DNS validator. Minecraft itself accepts
	 * bare hostnames, {@code host:port}, IPv4 and bracketed IPv6, so rejecting
	 * anything beyond structurally-dangerous input would break legitimate servers.</p>
	 */
	public static boolean isPlausibleAddress(String key) {
		if (key == null) {
			return false;
		}
		int length = key.length();
		if (length == 0 || length > MAX_ADDRESS_LENGTH) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			char c = key.charAt(i);
			if (Character.isISOControl(c) || c == 0xA7 || c == '"' || c == '\\') {
				return false;
			}
		}
		// Must contain at least one non-whitespace character.
		return !key.isBlank();
	}

	/**
	 * Plausibility check for a {@code servers} map key: either a generated server
	 * id or a structurally valid address.
	 */
	public static boolean isPlausibleKey(String key) {
		return ServerIdentity.isServerId(key) || isPlausibleAddress(key);
	}

	// ------------------------------------------------------------------
	// Text sanitisation (crash / injection prevention)
	// ------------------------------------------------------------------

	/**
	 * Sanitises a user-entered server display name before it is stored or
	 * rendered.
	 *
	 * <p>Rules:</p>
	 * <ul>
	 *   <li>{@code null} becomes {@code ""}.</li>
	 *   <li>Every ISO control character (C0/C1, including {@code \n}, {@code \t},
	 *       {@code \u00a7} section signs are <em>not</em> control but are still
	 *       stripped, see below) is removed.</li>
	 *   <li>Legacy formatting markers ({@code §}) are stripped <b>together with the
	 *       single code character that follows them</b>, so {@code "§rServer"} becomes
	 *       {@code "Server"} rather than the orphaned {@code "rServer"}. The
	 *       literal {@code &} is left alone because it is legitimate in server
	 *       names.</li>
	 *   <li>Malformed markup tails (a trailing {@code §} with nothing after it) are
	 *       likewise dropped.</li>
	 *   <li>JSON-hostile characters (only relevant for hand-edited files) are
	 *       removed: raw double quotes and backslashes.</li>
	 *   <li>The result is hard-truncated to {@link #MAX_NAME_LENGTH} characters
	 *       (counted on code points, never splitting a surrogate pair).</li>
	 * </ul>
	 */
	public static String sanitiseName(String input) {
		if (input == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(Math.min(input.length(), MAX_NAME_LENGTH));
		int count = 0;
		for (int i = 0; i < input.length() && count < MAX_NAME_LENGTH; ) {
			int cp = input.codePointAt(i);
			int chars = Character.charCount(cp);
			// A section sign introduces a one-character legacy format code: drop
			// both so we never leave an orphaned "r"/"l"/"c" letter behind.
			if (cp == 0xA7 /* § */) {
				i += chars;
				if (i < input.length()) {
					i += Character.charCount(input.codePointAt(i));
				}
				continue;
			}
			// Drop control characters, quotes and backslashes.
			boolean drop = Character.isISOControl(cp)
					|| cp == '"'
					|| cp == '\\';
			if (!drop) {
				sb.appendCodePoint(cp);
				count++;
			}
			i += chars;
		}
		return sb.toString();
	}

	/**
	 * Truncates {@code name} to at most {@code maxChars} code points without
	 * splitting a surrogate pair. Used as a last-resort guard on render paths.
	 */
	public static String truncate(String name, int maxChars) {
		if (name == null) {
			return "";
		}
		if (maxChars <= 0) {
			return "";
		}
		int cpCount = name.codePointCount(0, name.length());
		if (cpCount <= maxChars) {
			return name;
		}
		int end = name.offsetByCodePoints(0, maxChars);
		return name.substring(0, end);
	}

	// ------------------------------------------------------------------
	// Gson
	// ------------------------------------------------------------------

	public static Gson createGson() {
		return new GsonBuilder()
				.setPrettyPrinting()
				.disableHtmlEscaping()
				.registerTypeAdapter(ServerStyleConfig.class, new ConfigAdapter())
				.create();
	}

	public static final class ConfigAdapter
			implements JsonSerializer<ServerStyleConfig>, JsonDeserializer<ServerStyleConfig> {

		@Override
		public JsonElement serialize(ServerStyleConfig src, Type typeOfSrc,
									JsonSerializationContext context) {
			JsonObject root = new JsonObject();
			root.addProperty("schemaVersion", src.getSchemaVersion());
			// Written before "servers" so the hand-editable language line sits at
			// the top of the file, where a player looking for it will find it.
			root.addProperty("language", src.getLanguage());
			// v6: id -> (address, name) bindings. Written before "servers" so a
			// human reading the file sees what each style key refers to.
			JsonObject identities = new JsonObject();
			for (Map.Entry<String, ServerIdentity> e : src.getIdentities().entrySet()) {
				ServerIdentity identity = e.getValue();
				JsonObject obj = new JsonObject();
				obj.addProperty("ip", identity.ip());
				// A null name is omitted entirely: that is the "any name on this
				// address" legacy binding, and `"name": null` would be noise.
				if (identity.name() != null) {
					obj.addProperty("name", identity.name());
				}
				identities.add(e.getKey(), obj);
			}
			root.add("identities", identities);
			JsonObject servers = new JsonObject();
			for (Map.Entry<String, ServerStyle> e : src.getServers().entrySet()) {
				servers.add(e.getKey(), styleToJson(e.getValue()));
			}
			root.add("servers", servers);
			return root;
		}

		@Override
		public ServerStyleConfig deserialize(JsonElement json, Type typeOfT,
											JsonDeserializationContext context)
				throws JsonParseException {
			JsonObject root = JsonUtils.asObject(json, new JsonObject());
			int version = JsonUtils.asInt(root.get("schemaVersion"), 1);
			JsonObject servers = JsonUtils.asObject(root.get("servers"), new JsonObject());
			Map<String, ServerStyle> map = new LinkedHashMap<>();
			// Bounded iteration: a multi-megabyte member list cannot exhaust memory.
			int visited = 0;
			for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
				if (visited++ >= JsonUtils.MAX_ARRAY_ENTRIES) {
					break;
				}
				if (!e.getValue().isJsonObject()) {
					continue;
				}
				String key = normaliseKey(e.getKey());
				if (!isPlausibleKey(key)) {
					continue;
				}
				map.put(key, styleFromJson(e.getValue().getAsJsonObject()));
			}
			Map<String, ServerIdentity> ids = new LinkedHashMap<>();
			JsonObject identityRoot = JsonUtils.asObject(root.get("identities"), new JsonObject());
			int seen = 0;
			for (Map.Entry<String, JsonElement> e : identityRoot.entrySet()) {
				if (seen++ >= JsonUtils.MAX_ARRAY_ENTRIES) {
					break;
				}
				if (!(e.getValue() instanceof JsonObject obj)) {
					continue;
				}
				String id = e.getKey();
				if (!ServerIdentity.isServerId(id)) {
					continue;
				}
				String ip = ServerIdentity.normaliseAddress(
						JsonUtils.asString(obj.get("ip"), "", MAX_ADDRESS_LENGTH));
				if (ip.isEmpty() || !isPlausibleAddress(ip)) {
					continue;
				}
				String name = ServerIdentity.normaliseName(
						JsonUtils.asString(obj.get("name"), null, MAX_NAME_LENGTH));
				ids.put(id, new ServerIdentity(id, ip, name));
			}
			ServerStyleConfig cfg = new ServerStyleConfig(map, ids);
			// Always migrate up to the current schema; old files simply gain
			// defaults for the fields they lacked.
			cfg.setSchemaVersion(Math.max(version, SCHEMA_VERSION));
			// v5: the language choice. Absent in older files -> English, which is
			// exactly how those files behaved before translations existed.
			cfg.setLanguage(JsonUtils.asString(root.get("language"), DEFAULT_LANGUAGE, 16));
			return cfg;
		}
	}

	/**
	 * Serialises one style into the canonical on-disk shape.
	 *
	 * <p>Public so the preset store can write exactly the same object shape as the
	 * server map: a preset is just a style with a name attached, and keeping one
	 * serializer means a preset saved today is readable by any future build that
	 * understands the style schema.</p>
	 */
	public static JsonObject styleToJson(ServerStyle s) {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", s.isEnabled());
		o.addProperty("mode", s.getMode());
		JsonArray colors = new JsonArray();
		for (String c : s.getColors()) {
			colors.add(ColorUtils.sanitiseHex(c));
		}
		o.add("colors", colors);
		// v4: stop positions, parallel to colors. Written unconditionally so the
		// on-disk file is always self-describing; ServerStyle guarantees the list
		// matches the colour count and is pinned to 0/1.
		JsonArray positions = new JsonArray();
		for (float p : s.getPositions()) {
			positions.add(p);
		}
		o.add("positions", positions);
		o.addProperty("opacity", s.getOpacity());
		o.addProperty("bold", s.isBold());
		o.addProperty("italic", s.isItalic());
		// v3: miniCaps. Old files lack the key -> parser defaults to false.
		o.addProperty("miniCaps", s.isMiniCaps());
		// v2: alignment. null is impossible after ServerStyle.normalise(), but
		// coerce defensively so we never write `"alignment": null`.
		o.addProperty("alignment", ServerStyle.coerceAlignment(s.getAlignment()));
		return o;
	}

	/**
	 * Parses one style object from the canonical on-disk shape.
	 *
	 * <p>Deliberately tolerant: unknown fields are ignored, missing fields take
	 * defaults, and malformed values fall back to safe defaults without ever
	 * throwing. Shared by the server map and the preset store so both read the
	 * exact same format.</p>
	 */
	public static ServerStyle styleFromJson(JsonObject obj) {
		boolean enabled = JsonUtils.asBool(obj.get("enabled"), true);
		// Bounded read: a hand-edited file must not smuggle a large string into a
		// field that is only ever compared against two short constants.
		String modeRaw = JsonUtils.asString(obj.get("mode"), ServerStyle.MODE_SOLID, 16)
				.toUpperCase(Locale.ROOT);
		String mode = ServerStyle.MODE_GRADIENT.equals(modeRaw)
				? ServerStyle.MODE_GRADIENT
				: ServerStyle.MODE_SOLID;

		JsonArray arr = JsonUtils.asArray(obj.get("colors"), null);
		List<String> colors = new ArrayList<>();
		if (arr != null) {
			int limit = JsonUtils.limit(arr);
			for (int i = 0; i < limit; i++) {
				JsonElement el = arr.get(i);
				if (el instanceof JsonPrimitive p && p.isString()) {
					colors.add(ColorUtils.sanitiseHex(p.getAsString()));
				}
			}
		}
		if (colors.isEmpty()) {
			colors.add("#FFFFFF");
		}
		if (mode.equals(ServerStyle.MODE_SOLID) && colors.size() > 1) {
			colors.subList(1, colors.size()).clear();
		}

		// v4: stop positions. Absent in older files -> null, which ServerStyle
		// repairs into an even spread, exactly reproducing the old rendering.
		List<Float> positions = new ArrayList<>();
		JsonArray posArr = JsonUtils.asArray(obj.get("positions"), null);
		if (posArr != null) {
			int limit = JsonUtils.limit(posArr);
			for (int i = 0; i < limit; i++) {
				JsonElement el = posArr.get(i);
				if (el != null && el.isJsonPrimitive()) {
					positions.add(JsonUtils.asFloat(el, Float.NaN));
				}
			}
		}

		float opacity = JsonUtils.asFloat(obj.get("opacity"), 1.0f);
		boolean bold = JsonUtils.asBool(obj.get("bold"), false);
		boolean italic = JsonUtils.asBool(obj.get("italic"), false);
		// v3 field: absent in older files -> false, so old configs render unchanged.
		boolean miniCaps = JsonUtils.asBool(obj.get("miniCaps"), false);
		// v2 field: absent in older files -> coerceAlignment(null) -> LEFT.
		String alignment = JsonUtils.asString(obj.get("alignment"), null, 16);

		return new ServerStyle(enabled, mode, colors, positions, opacity, bold, italic, alignment,
				miniCaps);
	}
}