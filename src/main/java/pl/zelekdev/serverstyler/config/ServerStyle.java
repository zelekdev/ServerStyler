package pl.zelekdev.serverstyler.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable-ish data model for a single server's custom display styling.
 * Serialised to/from the JSON config file (schema v2).
 *
 * <p>JSON schema (per serverIp entry):</p>
 * <pre>
 * {
 *   "enabled": true,
 *   "mode": "SOLID" | "GRADIENT",
 *   "colors": ["#FF55AA", "#00AAFF", ...],   // 1 for SOLID, >= 2 for GRADIENT
 *   "opacity": 0.0 - 1.0,
 *   "bold": false,
 *   "italic": false,
 *   "miniCaps": false,
 *   "alignment": "LEFT" | "CENTER" | "RIGHT"
 * }
 * </pre>
 *
 * <p>Every mutator re-runs {@link #normalise()} so the object can never hold an
 * illegal mode, alignment, an empty colour list or an out-of-range opacity,
 * regardless of what a (possibly version-downgraded) config file contained.</p>
 */
public final class ServerStyle {

	public static final String MODE_SOLID = "SOLID";
	public static final String MODE_GRADIENT = "GRADIENT";

	public static final String ALIGN_LEFT = "LEFT";
	public static final String ALIGN_CENTER = "CENTER";
	public static final String ALIGN_RIGHT = "RIGHT";

	/** Maximum number of gradient stops the renderer and editor support. */
	public static final int MAX_GRADIENT_STOPS = 5;

	private boolean enabled;
	private String mode;
	private final List<String> colors;
	/**
	 * Normalised {@code 0..1} position of each colour stop along the text, parallel
	 * to {@link #colors}. The first stop is pinned to {@code 0} and the last to
	 * {@code 1}; only interior stops are free to move, so the gradient always spans
	 * the whole name. An uneven spacing (e.g. {@code 0, 0.2, 1}) is what lets one
	 * colour dominate the name.
	 */
	private final List<Float> positions;
	private float opacity;
	private boolean bold;
	private boolean italic;
	private String alignment;
	/** Render the name in Unicode small capitals (Mini Caps). */
	private boolean miniCaps;

	/** Design minimum opaque alpha used only for GUI rendering, never persisted. */
	public static final float MIN_RENDER_ALPHA = 0.10f;

	/** Minimum gap between adjacent stop positions, so stops never collapse onto each other. */
	public static final float MIN_STOP_GAP = 0.02f;

	public ServerStyle() {
		this(true, MODE_SOLID, new ArrayList<>(List.of("#FF55AA")), 1.0f, false, false,
				ALIGN_LEFT, false);
	}

	public ServerStyle(boolean enabled, String mode, List<String> colors, float opacity,
					   boolean bold, boolean italic) {
		this(enabled, mode, colors, null, opacity, bold, italic, ALIGN_LEFT, false);
	}

	public ServerStyle(boolean enabled, String mode, List<String> colors, float opacity,
					   boolean bold, boolean italic, String alignment) {
		this(enabled, mode, colors, null, opacity, bold, italic, alignment, false);
	}

	public ServerStyle(boolean enabled, String mode, List<String> colors, float opacity,
					   boolean bold, boolean italic, String alignment, boolean miniCaps) {
		this(enabled, mode, colors, null, opacity, bold, italic, alignment, miniCaps);
	}

	/**
	 * Full constructor, including explicit gradient stop positions.
	 *
	 * @param positions normalised {@code 0..1} positions parallel to {@code colors};
	 *                  {@code null}, short or malformed lists are repaired by
	 *                  {@link #normalisePositions()} into an even distribution
	 */
	public ServerStyle(boolean enabled, String mode, List<String> colors, List<Float> positions,
					   float opacity, boolean bold, boolean italic, String alignment,
					   boolean miniCaps) {
		this.enabled = enabled;
		this.mode = mode;
		this.colors = new ArrayList<>(colors == null ? List.of() : colors);
		this.positions = new ArrayList<>(positions == null ? List.of() : positions);
		this.opacity = opacity;
		this.bold = bold;
		this.italic = italic;
		this.alignment = alignment;
		this.miniCaps = miniCaps;
		normalise();
	}

	// ------------------------------------------------------------------
	// Validation / normalisation (always called after construction & parsing).
	// ------------------------------------------------------------------
	private void normalise() {
		if (!MODE_SOLID.equals(mode) && !MODE_GRADIENT.equals(mode)) {
			mode = MODE_SOLID;
		}
		// Every colour is forced through the sanitiser, so a hand-edited file can
		// never hand a null or malformed string to the renderer's parser.
		for (int i = 0; i < colors.size(); i++) {
			colors.set(i, ColorUtils.sanitiseHex(colors.get(i)));
		}
		if (colors.isEmpty()) {
			colors.add("#FFFFFF");
		}
		if (mode.equals(MODE_SOLID)) {
			while (colors.size() > 1) {
				colors.remove(colors.size() - 1);
			}
		} else if (colors.size() > MAX_GRADIENT_STOPS) {
			// A gradient may never carry more stops than the editor can show or the
			// renderer is budgeted for. Trimming here (rather than only in the editor)
			// means a hand-edited or future-version config cannot push the style past
			// the supported limit.
			while (colors.size() > MAX_GRADIENT_STOPS) {
				colors.remove(colors.size() - 1);
			}
		}
		normalisePositions();
		this.opacity = clampOpacity(opacity);
		this.alignment = coerceAlignment(alignment);
	}

	/**
	 * Repairs {@link #positions} so it is always exactly as long as {@link #colors},
	 * strictly increasing, and pinned to {@code [0, 1]} at the ends.
	 *
	 * <p>Rules, in order:</p>
	 * <ul>
	 *   <li>A missing / too-short list is filled by spreading the existing stops
	 *       evenly, which is what a config written before stop positions existed
	 *       needs.</li>
	 *   <li>Extra entries are dropped.</li>
	 *   <li>NaN and out-of-range values are replaced by the evenly-spaced value.</li>
	 *   <li>Ends are forced to exactly {@code 0} and {@code 1}.</li>
	 *   <li>Interior stops are pushed apart to at least {@link #MIN_STOP_GAP} so no
	 *       two stops ever coincide (which would make the gradient jump).</li>
	 * </ul>
	 */
	private void normalisePositions() {
		int n = colors.size();
		if (n <= 0) {
			positions.clear();
			return;
		}
		// Trim extras.
		while (positions.size() > n) {
			positions.remove(positions.size() - 1);
		}
		// Pad with the evenly-spaced value for that slot.
		while (positions.size() < n) {
			positions.add(n <= 1 ? 0.0f : (float) positions.size() / (float) (n - 1));
		}

		for (int i = 0; i < n; i++) {
			Float raw = positions.get(i);
			float value = raw == null || Float.isNaN(raw) || Float.isInfinite(raw)
					? (n <= 1 ? 0.0f : (float) i / (float) (n - 1))
					: Math.max(0.0f, Math.min(1.0f, raw));
			positions.set(i, value);
		}

		if (n == 1) {
			positions.set(0, 0.0f);
			return;
		}

		// Pin the ends; the gradient must always span the whole name.
		positions.set(0, 0.0f);
		positions.set(n - 1, 1.0f);

		// Push interior stops apart, left to right, so the sequence is strictly
		// increasing with a guaranteed minimum gap.
		float minGap = MIN_STOP_GAP;
		// If too many stops for the gap to fit, shrink the gap instead of overflowing.
		float maxGap = 1.0f / (float) (n - 1);
		if (minGap > maxGap) {
			minGap = maxGap * 0.5f;
		}
		for (int i = 1; i < n - 1; i++) {
			float lower = positions.get(i - 1) + minGap;
			float upper = 1.0f - (float) (n - 1 - i) * minGap;
			float value = Math.max(lower, Math.min(upper, positions.get(i)));
			positions.set(i, value);
		}
		positions.set(n - 1, 1.0f);
	}

	/**
	 * Coerces any possibly-null/garbage alignment string into one of the three
	 * supported constants. Returns {@link #ALIGN_LEFT} for {@code null} or
	 * unrecognised input, which makes loading an old config file (that has no
	 * {@code alignment} field) completely safe.
	 */
	public static String coerceAlignment(String raw) {
		if (raw == null) {
			return ALIGN_LEFT;
		}
		String upper = raw.trim().toUpperCase(Locale.ROOT);
		return switch (upper) {
			case ALIGN_CENTER -> ALIGN_CENTER;
			case ALIGN_RIGHT -> ALIGN_RIGHT;
			default -> ALIGN_LEFT;
		};
	}

	/** Clamp stored opacity into [0.0, 1.0]. */
	public static float clampOpacity(float value) {
		if (Float.isNaN(value)) {
			return 1.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, value));
	}

	/** Alpha used for actual rendering; keeps text visible even at 0% stored opacity. */
	public float renderAlpha() {
		// If the style is effectively fully transparent, still render at a faint floor
		// so the text remains legible/clickable.
		float render = opacity < MIN_RENDER_ALPHA ? MIN_RENDER_ALPHA : opacity;
		return Math.max(MIN_RENDER_ALPHA, render);
	}

	// ------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------
	public boolean isEnabled() {
		return enabled;
	}

	public String getMode() {
		return mode;
	}

	public boolean isSolid() {
		return MODE_SOLID.equals(mode);
	}

	public boolean isGradient() {
		return MODE_GRADIENT.equals(mode);
	}

	public List<String> getColors() {
		return List.copyOf(colors);
	}

	public String getColor(int index) {
		if (colors.isEmpty()) {
			return "#FFFFFF";
		}
		return colors.get(Math.max(0, Math.min(index, colors.size() - 1)));
	}

	/**
	 * Normalised {@code 0..1} position of stop {@code index} along the name.
	 * Always as long as {@link #getColors()} and pinned to {@code 0}/{@code 1}.
	 */
	public float getPosition(int index) {
		if (positions.isEmpty()) {
			return 0.0f;
		}
		return positions.get(Math.max(0, Math.min(index, positions.size() - 1)));
	}

	/** Defensive copy of all stop positions, parallel to {@link #getColors()}. */
	public List<Float> getPositions() {
		return List.copyOf(positions);
	}

	/** True when the stop is an interior one and therefore free to move. */
	public boolean isPositionMovable(int index) {
		return index > 0 && index < colors.size() - 1;
	}

	/**
	 * Moves an interior stop. End stops are pinned, so calls for index {@code 0} or
	 * the last index are ignored.
	 *
	 * <p>The value is clamped between its neighbours plus {@link #MIN_STOP_GAP}, so
	 * a stop can never be dragged past or onto another one.</p>
	 */
	public void setPosition(int index, float position) {
		if (colors.size() < 3 || !isPositionMovable(index)) {
			return;
		}
		if (Float.isNaN(position)) {
			return;
		}
		float lower = positions.get(index - 1) + MIN_STOP_GAP;
		float upper = positions.get(index + 1) - MIN_STOP_GAP;
		positions.set(index, Math.max(lower, Math.min(upper, position)));
	}

	/** The first colour; for SOLID this is the only colour. */
	public String getPrimaryColor() {
		return getColor(0);
	}

	/** RGB int of the primary colour (0xRRGGBB, opaque). */
	public int getPrimaryRgb() {
		return ColorUtils.parseHex(getPrimaryColor());
	}

	public float getOpacity() {
		return opacity;
	}

	public boolean isBold() {
		return bold;
	}

	public boolean isItalic() {
		return italic;
	}

	/** Whether the display name is rendered in Unicode small capitals (Mini Caps). */
	public boolean isMiniCaps() {
		return miniCaps;
	}

	/** One of {@link #ALIGN_LEFT}, {@link #ALIGN_CENTER}, {@link #ALIGN_RIGHT}. */
	public String getAlignment() {
		return alignment;
	}

	public boolean isAlignCenter() {
		return ALIGN_CENTER.equals(alignment);
	}

	public boolean isAlignRight() {
		return ALIGN_RIGHT.equals(alignment);
	}

	public boolean isAlignLeft() {
		return ALIGN_LEFT.equals(alignment);
	}

	// ------------------------------------------------------------------
	// Mutators (used by the GUI editor; each normalises).
	// ------------------------------------------------------------------
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void setMode(String mode) {
		this.mode = mode;
		normalise();
	}

	public void setSolid() {
		this.mode = MODE_SOLID;
		while (colors.size() > 1) {
			colors.remove(colors.size() - 1);
		}
		normalise();
	}

	public void setGradient(List<String> newColors) {
		this.mode = MODE_GRADIENT;
		this.colors.clear();
		if (newColors != null) {
			this.colors.addAll(newColors);
		}
		// Resetting the colours drops any custom spacing; normalise() re-spreads them
		// evenly, which is the sane default for a fresh gradient.
		this.positions.clear();
		normalise();
	}

	/**
	 * Moves a gradient stop's spacing without touching its colour.
	 *
	 * @see #setPosition(int, float)
	 */
	public void setGradientPositions(List<Float> newPositions) {
		this.positions.clear();
		if (newPositions != null) {
			this.positions.addAll(newPositions);
		}
		normalise();
	}

	public void setColor(int index, String hex) {
		index = Math.max(0, index);
		String cleaned = ColorUtils.sanitiseHex(hex);
		if (index >= colors.size()) {
			for (int i = colors.size(); i <= index; i++) {
				colors.add("#FFFFFF");
			}
		}
		colors.set(index, cleaned);
		if (mode.equals(MODE_SOLID)) {
			while (colors.size() > 1) {
				colors.remove(colors.size() - 1);
			}
		}
		normalise();
	}

	/**
	 * Adds a new gradient stop (clamped to {@link #MAX_GRADIENT_STOPS}) and returns
	 * the new count.
	 *
	 * <p>With two stops a new one is inserted <b>between</b> them, at the midpoint,
	 * coloured as the blend of the two neighbours. That matches how a gradient
	 * editor is expected to behave: adding a stop puts it in the middle of the
	 * space it will occupy, ready to be dragged either way, rather than appending
	 * it at the far end where it would change nothing until moved.</p>
	 */
	public int addGradientStop() {
		if (!mode.equals(MODE_GRADIENT)) {
			setGradient(new ArrayList<>(colors));
		}
		if (colors.size() >= MAX_GRADIENT_STOPS) {
			return colors.size();
		}

		int insertAt;
		float insertPos;
		String newHex;
		if (colors.size() == 1) {
			insertAt = 1;
			insertPos = 1.0f;
			newHex = ColorUtils.toHex(ColorUtils.parseHex(colors.get(0)));
		} else {
			// Always split the widest gap, so adds stay visually balanced when a user
			// adds more than one stop in a row.
			int best = 0;
			float widest = -1.0f;
			for (int i = 0; i < positions.size() - 1; i++) {
				float gap = positions.get(i + 1) - positions.get(i);
				if (gap > widest) {
					widest = gap;
					best = i;
				}
			}
			insertAt = best + 1;
			insertPos = (positions.get(best) + positions.get(best + 1)) * 0.5f;

			// Blend the two neighbours so the new chip is visibly the in-between
			// colour instead of a duplicate of a neighbour.
			int fromRgb = ColorUtils.parseHex(colors.get(best)) & 0xFFFFFF;
			int toRgb = ColorUtils.parseHex(colors.get(best + 1)) & 0xFFFFFF;
			newHex = ColorUtils.toHex(ColorUtils.lerp(fromRgb, toRgb, 0.5f) & 0xFFFFFF);
		}

		colors.add(insertAt, newHex);
		positions.add(insertAt, insertPos);
		normalise();
		return colors.size();
	}

	/**
	 * Removes the given stop, but never below two colours. End stops are not
	 * removable - the gradient must keep both of its endpoints.
	 *
	 * @return the new stop count
	 */
	public int removeGradientStopAt(int index) {
		if (colors.size() <= 2 || index <= 0 || index >= colors.size() - 1) {
			return colors.size();
		}
		colors.remove(index);
		if (index < positions.size()) {
			positions.remove(index);
		}
		normalise();
		return colors.size();
	}

	/** Removes the last gradient stop (minimum 2 for gradient). Returns new count. */
	public int removeGradientStop() {
		if (colors.size() > 2) {
			colors.remove(colors.size() - 1);
			if (!positions.isEmpty()) {
				positions.remove(positions.size() - 1);
			}
			normalise();
		}
		return colors.size();
	}

	public int gradientStopCount() {
		return colors.size();
	}

	public void setOpacity(float opacity) {
		this.opacity = clampOpacity(opacity);
	}

	public void setBold(boolean bold) {
		this.bold = bold;
	}

	public void setItalic(boolean italic) {
		this.italic = italic;
	}

	public void setMiniCaps(boolean miniCaps) {
		this.miniCaps = miniCaps;
	}

	/** Sets the alignment; any unrecognised value falls back to LEFT. */
	public void setAlignment(String alignment) {
		this.alignment = coerceAlignment(alignment);
	}

	public ServerStyle copy() {
		return new ServerStyle(enabled, mode, colors, positions, opacity, bold, italic, alignment,
				miniCaps);
	}

	/**
	 * Copies every field of {@code other} into <em>this</em> instance, in place.
	 *
	 * <p>Used when a preset is applied: the editor holds a live reference to the
	 * working style, and the host screen persists that exact instance, so the
	 * preset has to be merged into it rather than replacing the reference.</p>
	 *
	 * <p>Self-assignment is a no-op, and the result is re-normalised so a preset
	 * can never leave the object in an illegal state.</p>
	 */
	public void copyFrom(ServerStyle other) {
		if (other == null || other == this) {
			return;
		}
		this.enabled = other.enabled;
		this.mode = other.mode;
		this.colors.clear();
		this.colors.addAll(other.colors);
		this.positions.clear();
		this.positions.addAll(other.positions);
		this.opacity = other.opacity;
		this.bold = other.bold;
		this.italic = other.italic;
		this.alignment = other.alignment;
		this.miniCaps = other.miniCaps;
		normalise();
	}
}