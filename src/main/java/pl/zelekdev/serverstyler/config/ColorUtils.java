package pl.zelekdev.serverstyler.config;

import java.util.List;

/**
 * Pure colour math: hex string parsing/sanitisation, RGB/HSV conversion,
 * and ARGB interpolation between gradient stops.
 *
 * <p>All colour values are packed as {@code 0xAARRGGBB} unless stated otherwise.</p>
 */
public final class ColorUtils {

	private ColorUtils() {
	}

	/** Hex without "#", e.g. "FF55AA". */
	public static final String FALLBACK_HEX = "FFFFFF";
	/** Opaque white as ARGB int. */
	public static final int WHITE_ARGB = 0xFF_FF_FF_FF;

	// ------------------------------------------------------------------
	// Hex string helpers
	// ------------------------------------------------------------------

	/**
	 * Parses a hex colour string. Accepts "#RGB", "RGB", "#RRGGBB", "RRGGBB",
	 * "#RRGGBBAA" (alpha ignored), 0x-prefixed, "0xFFFFFF". Returns 0xRRGGBB
	 * (opaque) or the packed ARGB if an alpha component is present.
	 *
	 * <p>Returns {@link #WHITE_ARGB} on any malformed input.</p>
	 */
	public static int parseHex(String hex) {
		if (hex == null) {
			return WHITE_ARGB;
		}
		String cleaned = stripPrefix(hex.trim()).replace(" ", "");
		if (cleaned.isEmpty() || cleaned.length() > 8) {
			// Anything longer than #RRGGBBAA cannot be a colour; reject before any
			// parse attempt rather than relying on NumberFormatException.
			return WHITE_ARGB;
		}
		try {
			switch (cleaned.length()) {
				case 3: {
					String r = expanded(cleaned.charAt(0));
					String g = expanded(cleaned.charAt(1));
					String b = expanded(cleaned.charAt(2));
					return 0xFF000000 | (Integer.parseInt(r + r, 16) << 16)
							| (Integer.parseInt(g + g, 16) << 8)
							| Integer.parseInt(b + b, 16);
				}
				case 6: {
					return 0xFF000000 | Integer.parseInt(cleaned, 16);
				}
				case 8: {
					long v = Long.parseLong(cleaned, 16);
					int alpha = (int) ((v >>> 24) & 0xFF);
					int rgb = (int) (v & 0xFFFFFF);
					return (alpha << 24) | rgb;
				}
				default:
					return WHITE_ARGB;
			}
		} catch (NumberFormatException e) {
			return WHITE_ARGB;
		}
	}

	/**
	 * Sanitises an arbitrary user string into a canonical, upper-case
	 * {@code #RRGGBB} form.
	 *
	 * <p>The input must be a hex colour in one of the accepted shapes:
	 * {@code #RGB}, {@code RGB}, {@code #RRGGBB}, {@code RRGGBB}, {@code #RRGGBBAA}
	 * (alpha dropped), or the same with an {@code 0x} prefix. Anything else -
	 * including text that merely <em>contains</em> hex digits such as
	 * {@code "nonsense"} - falls back to {@code #FFFFFF}.</p>
	 *
	 * <p>Rejecting rather than salvaging is deliberate: silently turning a typo into
	 * a plausible-looking colour is worse than showing white, because the user would
	 * then wonder why their name is suddenly red.</p>
	 */
	public static String sanitiseHex(String input) {
		if (input == null) {
			return '#' + FALLBACK_HEX;
		}
		String raw = stripPrefix(input.trim());
		// Bounded scan: a multi-megabyte hand-edited value costs nothing.
		if (raw.length() > 8) {
			return '#' + FALLBACK_HEX;
		}
		for (int i = 0; i < raw.length(); i++) {
			if (!isHexDigit(raw.charAt(i))) {
				return '#' + FALLBACK_HEX;
			}
		}
		String cleaned = raw.toUpperCase(java.util.Locale.ROOT);
		if (cleaned.isEmpty()) {
			return '#' + FALLBACK_HEX;
		}
		// 3-digit (#RGB) and 4-digit (#RGBA, alpha dropped) expand to 6.
		if (cleaned.length() == 3 || cleaned.length() == 4) {
			StringBuilder full = new StringBuilder(6);
			for (int i = 0; i < 3; i++) {
				char c = cleaned.charAt(i);
				full.append(c).append(c);
			}
			cleaned = full.toString();
		} else if (cleaned.length() == 8) {
			cleaned = cleaned.substring(0, 6);
		} else if (cleaned.length() < 6) {
			return '#' + FALLBACK_HEX;
		}
		return '#' + cleaned;
	}

	private static boolean isHexDigit(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	public static String toHex(int rgb) {
		int v = rgb & 0xFFFFFF;
		return String.format("%06X", v);
	}

	/** Returns colour with alpha applied: {@code (alpha<<24)|rgb}. */
	public static int withAlpha(float alpha, int rgb) {
		return (alphaByte(alpha) << 24) | (rgb & 0xFFFFFF);
	}

	/**
	 * Quantises a {@code 0..1} alpha to a {@code 0..255} byte.
	 *
	 * <p>NaN and infinities collapse to fully opaque, matching the "never render
	 * invisible text" rule the renderer applies elsewhere.</p>
	 */
	public static int alphaByte(float alpha) {
		if (Float.isNaN(alpha)) {
			return 255;
		}
		return (int) (clamp01(alpha) * 255.0f + 0.5f);
	}

	/** "AA"/"RR"/... single char expander for 3-digit hex. */
	private static String expanded(char c) {
		return Character.toString(c);
	}

	private static String stripPrefix(String s) {
		if (s.startsWith("#")) {
			return s.substring(1);
		}
		if (s.startsWith("0x") || s.startsWith("0X")) {
			return s.substring(2);
		}
		return s;
	}

	public static float clamp01(float v) {
		if (Float.isNaN(v)) {
			return 0.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, v));
	}

	// ------------------------------------------------------------------
	// RGB / HSV
	// ------------------------------------------------------------------

	public static final class Hsv {
		public float h; // 0..360
		public float s; // 0..1
		public float v; // 0..1

		public Hsv(float h, float s, float v) {
			this.h = h;
			this.s = s;
			this.v = v;
		}
	}

	public static Hsv rgbToHsv(int color) {
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		float rf = r / 255.0f;
		float gf = g / 255.0f;
		float bf = b / 255.0f;
		float max = Math.max(rf, Math.max(gf, bf));
		float min = Math.min(rf, Math.min(gf, bf));
		float delta = max - min;

		float h;
		if (delta == 0.0f) {
			h = 0.0f;
		} else if (max == rf) {
			h = 60.0f * (((gf - bf) / delta) % 6.0f);
		} else if (max == gf) {
			h = 60.0f * (((bf - rf) / delta) + 2.0f);
		} else {
			h = 60.0f * (((rf - gf) / delta) + 4.0f);
		}
		if (h < 0.0f) {
			h += 360.0f;
		}
		float s = max == 0.0f ? 0.0f : delta / max;
		return new Hsv(h, s, max);
	}

	/** Hue (0..1) -> opaque fully-saturated RGB int (0xRRGGBB). */
	public static int hsvToRgb(float hue01) {
		float h = ((hue01 % 1.0f) + 1.0f) % 1.0f * 360.0f;
		return hsvToRgb(new Hsv(h, 1.0f, 1.0f));
	}

	public static int hsvToRgb(Hsv hsv) {
		float c = hsv.v * hsv.s;
		float hp = ((hsv.h % 360.0f) + 360.0f) % 360.0f / 60.0f;
		float x = c * (1.0f - Math.abs((hp % 2.0f) - 1.0f));
		float r = 0, g = 0, b = 0;
		if (hp < 1) {
			r = c; g = x;
		} else if (hp < 2) {
			r = x; g = c;
		} else if (hp < 3) {
			g = c; b = x;
		} else if (hp < 4) {
			g = x; b = c;
		} else if (hp < 5) {
			r = x; b = c;
		} else {
			r = c; b = x;
		}
		float m = hsv.v - c;
		return 0xFF000000
				| ((int) ((r + m) * 255.0f) << 16)
				| ((int) ((g + m) * 255.0f) << 8)
				| (int) ((b + m) * 255.0f);
	}

	// ------------------------------------------------------------------
	// Interpolation / compositing
	// ------------------------------------------------------------------

	/**
	 * Linear interpolation in ARGB space between two packed colours at {@code t} (0..1).
	 * Interpolates alpha and each RGB channel independently.
	 */
	public static int lerp(int fromArgb, int toArgb, float t) {
		t = clamp01(t);
		int a1 = (fromArgb >>> 24) & 0xFF, r1 = (fromArgb >> 16) & 0xFF;
		int g1 = (fromArgb >> 8) & 0xFF, b1 = fromArgb & 0xFF;
		int a2 = (toArgb >>> 24) & 0xFF, r2 = (toArgb >> 16) & 0xFF;
		int g2 = (toArgb >> 8) & 0xFF, b2 = toArgb & 0xFF;
		return ((int) (a1 + (a2 - a1) * t) << 24)
				| ((int) (r1 + (r2 - r1) * t) << 16)
				| ((int) (g1 + (g2 - g1) * t) << 8)
				| (int) (b1 + (b2 - b1) * t);
	}

	/** Alpha-scaled version of {@link #lerp}: multiplies interpolated alpha by {@code globalAlpha}. */
	public static int lerpWithGlobalAlpha(int fromRgb, int toRgb, float t, float globalAlpha) {
		int base = lerp(fromRgb | 0xFF000000, toRgb | 0xFF000000, t);
		return withAlpha(clamp01(globalAlpha), base);
	}

	/**
	 * Evaluates a multi-stop gradient at {@code t} with evenly spaced stops.
	 * Colors are opaque RBG ints; the alpha is derived from {@code globalAlpha}.
	 */
	public static int gradientAt(List<String> colors, float t, float globalAlpha) {
		return gradientAt(colors, null, t, globalAlpha);
	}

	/**
	 * Evaluates a multi-stop gradient at {@code t} with explicit stop positions.
	 *
	 * <p>{@code positions} is parallel to {@code colors}; a {@code null}, short or
	 * malformed list falls back to even spacing. Uneven positions are what let one
	 * colour occupy more of the name than another (e.g. {@code 0, 0.2, 1} keeps the
	 * middle colour confined to the first fifth).</p>
	 */
	public static int gradientAt(List<String> colors, List<Float> positions, float t,
								 float globalAlpha) {
		int n = colors == null ? 0 : colors.size();
		if (n <= 0) {
			return withAlpha(globalAlpha, 0xFFFFFF);
		}
		if (n == 1) {
			return withAlpha(globalAlpha, parseHex(colors.get(0)));
		}
		t = clamp01(t);

		boolean usable = positions != null && positions.size() == n;
		if (!usable) {
			// Even spacing is the historical behaviour; keep it byte-identical so
			// existing configs render exactly as before.
			float scaled = t * (n - 1);
			int idx = Math.min((int) scaled, n - 2);
			float local = scaled - idx;
			return lerpWithGlobalAlpha(parseHex(colors.get(idx)), parseHex(colors.get(idx + 1)),
					local, globalAlpha);
		}

		float t0 = clamp01(positions.get(0));
		float tLast = clamp01(positions.get(n - 1));
		// Before the first stop / after the last stop: hold the end colour.
		if (t <= t0) {
			return withAlpha(globalAlpha, parseHex(colors.get(0)));
		}
		if (t >= tLast) {
			return withAlpha(globalAlpha, parseHex(colors.get(n - 1)));
		}

		for (int i = 0; i < n - 1; i++) {
			float start = clamp01(positions.get(i));
			float end = clamp01(positions.get(i + 1));
			if (t >= start && t <= end) {
				float span = end - start;
				float local = span <= 0.0f ? 0.0f : (t - start) / span;
				return lerpWithGlobalAlpha(parseHex(colors.get(i)), parseHex(colors.get(i + 1)),
						local, globalAlpha);
			}
		}
		// Unreachable with normalised positions, but never return garbage.
		return withAlpha(globalAlpha, parseHex(colors.get(n - 1)));
	}
}