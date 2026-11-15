package pl.zelekdev.serverstyler.render;

import pl.zelekdev.serverstyler.config.ColorUtils;
import pl.zelekdev.serverstyler.config.ServerStyle;
import pl.zelekdev.serverstyler.config.ServerStyleConfig;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The heart of ServerStyler: turns a plain display string plus a
 * {@link ServerStyle} into a fully styled {@link MutableComponent}.
 *
 * <p>Each character is emitted as its own component with an independently
 * interpolated colour, which is what produces the multi-stop linear gradient.
 * Colours are computed in packed ARGB ({@code (alpha << 24) | rgb}) internally
 * and then folded into the {@code 0xRRGGBB} range that {@link TextColor}
 * accepts, with the alpha pre-composited against the list background.</p>
 *
 * <h3>Alpha handling</h3>
 * <p>{@link TextColor} can only carry an opaque {@code 0xRRGGBB} value &mdash; the
 * vanilla glyph pipeline has no per-span alpha channel. To honour the user's
 * requested opacity without touching the render pipeline we emulate
 * transparency by compositing the interpolated colour over the dark multiplayer
 * list background ({@link #LIST_BACKGROUND}) using proper source-over alpha
 * blending.</p>
 *
 * <h3>Alignment</h3>
 * <p>{@link #alignedX(int, int, int, String)} converts a left-based anchor into
 * the correct draw X for LEFT/CENTER/RIGHT within a known pixel span. It is
 * deliberately free of any client-only types so it can be shared by the
 * multiplayer-list hook and the live {@code EditBox} preview.</p>
 *
 * <h3>Length safeguards</h3>
 * <p>Every entry point truncates to {@link ServerStyleConfig#MAX_NAME_LENGTH} and
 * sanitises control characters first, so no path can ever interpolate an
 * unbounded string (which would risk a frame freeze).</p>
 */
public final class TextStylingEngine {

	/** Background the multiplayer list rows are drawn over (dark translucent overlay). */
	public static final int LIST_BACKGROUND = 0xFF000000;

	private TextStylingEngine() {
	}

	// ------------------------------------------------------------------
	// Public building API
	// ------------------------------------------------------------------

	/**
	 * Builds the styled component for {@code text} using {@code style}.
	 *
	 * @param text  the plain text to colour (never contains markup)
	 * @param style the style to apply; {@code null} yields a plain literal
	 */
	public static MutableComponent buildComponent(String text, ServerStyle style) {
		String clean = prepare(text);
		return buildComponent(clean, style, 0, clean.length());
	}

	/**
	 * Builds the styled component for a <em>slice</em> of a longer string.
	 *
	 * <p>{@code EditBox} splits its visible text at the caret and calls the
	 * formatter twice: once for the prefix and once for the suffix. If each slice
	 * restarted its own gradient, the first character after the caret would begin a
	 * fresh gradient &mdash; the bug where one letter visibly lacked the word's
	 * gradient. By interpolating over the <b>absolute</b> index and the <b>full</b>
	 * string length the slices line up seamlessly.</p>
	 *
	 * @param text                the slice to colour
	 * @param style               the style to apply; {@code null} yields a plain literal
	 * @param firstCharacterIndex absolute index of {@code text[0]} in the full string
	 * @param totalLength         length of the full, unsliced string
	 */
	public static MutableComponent buildComponent(String text, ServerStyle style,
												  int firstCharacterIndex, int totalLength) {
		String slice = prepare(text);
		if (slice.isEmpty()) {
			return Component.empty();
		}
		if (style == null) {
			return Component.literal(slice);
		}

		boolean gradient = style.isGradient();
		List<String> colors = style.getColors();
		List<Float> positions = style.getPositions();
		float alpha = style.renderAlpha();
		boolean bold = style.isBold();
		boolean italic = style.isItalic();

		int safeFirst = Math.max(0, firstCharacterIndex);
		int length = slice.length();
		int span = Math.max(Math.min(totalLength, ServerStyleConfig.MAX_NAME_LENGTH), safeFirst + length);

		MutableComponent out = Component.empty();
		for (int i = 0; i < length; i++) {
			int argb = argbAt(colors, positions, gradient, safeFirst + i, span, alpha);
			int visible = compositeOverBackground(argb);
			Style charStyle = Style.EMPTY.withColor(TextColor.fromRgb(visible));
			if (bold) {
				charStyle = charStyle.withBold(true);
			}
			if (italic) {
				charStyle = charStyle.withItalic(true);
			}
			out.append(Component.literal(String.valueOf(slice.charAt(i))).withStyle(charStyle));
		}
		return out;
	}

	/**
	 * Builds a {@link FormattedCharSequence} for {@code text}.
	 * Used by the {@code EditBox} formatter hook to render the live preview
	 * directly inside the server-name field (WYSIWYG).
	 */
	public static FormattedCharSequence buildFormatted(String text, ServerStyle style) {
		return buildComponent(text, style).getVisualOrderText();
	}

	/**
	 * Slice-aware variant used by the {@code EditBox} formatter hook.
	 *
	 * <p>{@code EditBox} passes {@code (slice, firstCharacterIndex)} and may call
	 * the formatter twice (prefix + suffix around the caret). We resolve the full
	 * string length from the owning field so both slices share one continuous
	 * gradient.</p>
	 */
	public static FormattedCharSequence buildFormatted(String text, ServerStyle style,
													   int firstCharacterIndex, int totalLength) {
		int total = totalLength > 0 ? totalLength : text == null ? 0 : text.length();
		return buildComponent(text, style, firstCharacterIndex, total).getVisualOrderText();
	}

	/**
	 * Strips control characters / legacy markers and truncates to the hard name
	 * limit. Safe for {@code null}.
	 */
	public static String prepare(String text) {
		return ServerStyleConfig.truncate(ServerStyleConfig.sanitiseName(text),
				ServerStyleConfig.MAX_NAME_LENGTH);
	}

	// ------------------------------------------------------------------
	// Mini Caps (Unicode small capitals)
	// ------------------------------------------------------------------

	/**
	 * Unicode small-capital / small-letter forms for {@code A}..{@code Z}.
	 *
	 * <p>Every code point below was verified to exist as a real glyph in vanilla's
	 * {@code minecraft:font/nonlatin_european.png} sheet, so no custom font resource
	 * is needed. {@link #MINI_CAPS_LOWER} additionally accepts the lowercase input
	 * letters and maps them to the same target.</p>
	 *
	 * <p>{@code X} is the one gap in Unicode: there is no small-capital X
	 * (U+1D00..U+1D2B skips it and U+A7B0-range has no X either). It falls back to
	 * a plain lowercase {@code x}, which is visually the correct shape anyway.</p>
	 */
	private static final char[] MINI_CAPS_UPPER = {
			'\u1D00', // A
			'\u0299', // B
			'\u1D04', // C
			'\u1D05', // D
			'\u1D07', // E
			'\uA730', // F
			'\u0262', // G
			'\u029C', // H
			'\u026A', // I
			'\u1D0A', // J
			'\u1D0B', // K
			'\u029F', // L
			'\u1D0D', // M
			'\u0274', // N
			'\u1D0F', // O
			'\u1D18', // P
			'\uA7AF', // Q (small-capital Q, added in Unicode 7.0)
			'\u0280', // R
			'\uA731', // S
			'\u1D1B', // T
			'\u1D1C', // U
			'\u1D20', // V
			'\u1D21', // W
			'x',      // X - no small-capital X exists; lowercase x is the right shape
			'\u028F', // Y
			'\u1D22'  // Z
	};

	/**
	 * Polish (and general Latin) letters that have no small-capital counterpart in
	 * the vanilla font atlas. Combining diacritics such as U+0301 (acute) and
	 * U+0328 (ogonek) are <b>not</b> part of {@code nonlatin_european.png}, so
	 * re-decorating a base small capital is impossible; the only option that renders
	 * without missing-glyph boxes is to strip the diacritic and keep the base letter.
	 */
	private static final String POLISH_FROM = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ";
	private static final String POLISH_TO = "acelnoszzACELNOSZZ";

	/**
	 * Converts {@code text} to Unicode small capitals (Mini Caps).
	 *
	 * <p>Behaviour, in order:</p>
	 * <ol>
	 *   <li>Polish/Latin diacritics are folded to their base letter
	 *       ({@code ą -> a}, {@code ł -> l}, {@code ó -> o}, ...), because the
	 *       vanilla atlas has no combining ogonek/acute and the styled glyphs would
	 *       otherwise render as missing-glyph squares.</li>
	 *   <li>{@code A}..{@code Z} and {@code a}..{@code z} are replaced by their
	 *       small-capital form.</li>
	 *   <li>Digits, punctuation, spaces and every other code point are preserved
	 *       verbatim, so {@code "Serwer 1.20"} becomes {@code "ꜱᴇʀᴡᴇʀ 1.20"}.</li>
	 * </ol>
	 *
	 * <p>The output length can differ from the input length (the diacritic fold is
	 * 1:1 but a small capital may be a different code unit), so callers must not
	 * assume an index-for-index mapping. Alignment and truncation are applied to the
	 * converted string, which is why the pipeline is {@code miniCaps -> fit -> align}.</p>
	 *
	 * @param text the plain input, may be {@code null}
	 * @return the Mini Caps rendering, or the input unchanged when {@code null}/empty
	 */
	public static String toMiniCaps(String text) {
		if (text == null || text.isEmpty()) {
			return text == null ? "" : text;
		}
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			int chars = Character.charCount(cp);
			i += chars;

			// Fold Polish diacritics to the plain Latin base letter first.
			cp = foldPolishDiacritic(cp);

			if (cp >= 'A' && cp <= 'Z') {
				out.append(MINI_CAPS_UPPER[cp - 'A']);
			} else if (cp >= 'a' && cp <= 'z') {
				out.append(MINI_CAPS_UPPER[cp - 'a']);
			} else {
				out.appendCodePoint(cp);
			}
		}
		return out.toString();
	}

	/**
	 * Maps a Polish diacritic code point to its base letter code point, returning
	 * the input unchanged when it is not a foldable diacritic.
	 *
	 * <p>Returns {@code int}, not {@code char}: a cast to {@code char} would
	 * silently truncate any supplementary-plane code point (an emoji, say) to its
	 * low 16 bits and corrupt the name.</p>
	 */
	private static int foldPolishDiacritic(int cp) {
		if (cp < 0 || cp > 0xFFFF) {
			return cp;
		}
		int idx = POLISH_FROM.indexOf((char) cp);
		return idx < 0 ? cp : POLISH_TO.charAt(idx);
	}

	/**
	 * Applies {@code style}'s Mini Caps setting, if enabled.
	 *
	 * <p>Provided so every rendering path (multiplayer list, live {@code EditBox}
	 * preview, colour picker preview) shares one implementation and can never drift
	 * apart on whether the conversion happened before or after sanitisation.</p>
	 */
	public static String applyMiniCaps(String text, ServerStyle style) {
		if (style == null || !style.isMiniCaps()) {
			return text;
		}
		return toMiniCaps(text);
	}

	// ------------------------------------------------------------------
	// Width-aware trimming (dynamic, resolution independent)
	// ------------------------------------------------------------------

	/** Appended when a name does not fit the row. */
	public static final String ELLIPSIS = "...";

	/**
	 * Shortens {@code text} so that it renders within {@code maxWidth} pixels,
	 * appending {@link #ELLIPSIS} when anything had to be dropped.
	 *
	 * <p>The measurement is delegated to {@code measure}, so this stays free of any
	 * client-only type and works identically for the multiplayer list and the live
	 * {@code EditBox} preview. Because the budget is passed in per call, the result
	 * automatically adapts to a window resize, a GUI-scale change, or a wider player
	 * count string &mdash; no cached pixel constants are involved.</p>
	 *
	 * <p>Truncation walks back one <b>code point</b> at a time, so surrogate pairs
	 * (emoji, most CJK extensions) are never split into lone halves.</p>
	 *
	 * @param text     the text to fit (may be {@code null})
	 * @param maxWidth available pixel width; {@code <= 0} yields an empty string
	 * @param measure  measures the rendered pixel width of a candidate string
	 * @return the original text when it fits, otherwise a prefix plus
	 *         {@link #ELLIPSIS}; an empty string when not even the ellipsis fits
	 */
	public static String trimToWidth(String text, int maxWidth, ToIntFunction<String> measure) {
		if (text == null || text.isEmpty() || measure == null || maxWidth <= 0) {
			return "";
		}
		if (measure.applyAsInt(text) <= maxWidth) {
			return text;
		}
		int ellipsisWidth = measure.applyAsInt(ELLIPSIS);
		if (ellipsisWidth >= maxWidth) {
			// Not even room for the dots: draw nothing rather than bleed over the row.
			return "";
		}
		int budget = maxWidth - ellipsisWidth;
		int end = text.length();
		while (end > 0) {
			end = text.offsetByCodePoints(end, -1);
			if (measure.applyAsInt(text.substring(0, end)) <= budget) {
				break;
			}
		}
		if (end <= 0) {
			return "";
		}
		return text.substring(0, end) + ELLIPSIS;
	}

	/**
	 * Hard character cap, applied <b>before</b> any pixel measurement.
	 *
	 * <p>A pixel-only budget is not enough: a name can technically fit a row's
	 * remaining pixels yet still look absurdly long, crowding the player count.
	 * Capping the code-point count first keeps every row readable regardless of
	 * which glyphs were used (wide CJK characters and narrow {@code i}/{@code l}
	 * differ by a factor of five in the same pixel budget).</p>
	 *
	 * <p>Surrogate pairs are counted as one character and never split.</p>
	 *
	 * @param text  the text to cap (may be {@code null})
	 * @param limit maximum number of code points; {@code <= 0} yields an empty string
	 * @return the text unchanged when within {@code limit}, otherwise its prefix
	 */
	public static String limitChars(String text, int limit) {
		if (text == null || text.isEmpty() || limit <= 0) {
			return "";
		}
		int codePoints = text.codePointCount(0, text.length());
		if (codePoints <= limit) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, limit));
	}

	/**
	 * Fits {@code text} to a list row in one pass: sanitise, cap the character
	 * count, then fit the remaining pixels, always marking truncation with
	 * {@link #ELLIPSIS}.
	 *
	 * <p>Doing this in a single method matters because the two budgets interact. If
	 * the character cap trims the text to something that happens to fit the pixel
	 * budget, a naive implementation stops there and renders a silently cut-off name
	 * with no ellipsis, which reads like a genuine short name. Here any truncation
	 * &mdash; by characters or by pixels &mdash; appends the ellipsis exactly once.</p>
	 *
	 * @param text      raw display name (may be {@code null})
	 * @param charLimit maximum code points to keep before considering pixels
	 * @param maxWidth  available pixel width; {@code <= 0} yields an empty string
	 * @param measure   measures the rendered pixel width of a candidate string
	 * @return the name, shortened and ellipsis-marked as needed; empty when nothing
	 *         (not even the ellipsis) fits
	 */
	public static String fitToRow(String text, int charLimit, int maxWidth,
								  ToIntFunction<String> measure) {
		String clean = prepare(text);
		// A whitespace-only name must render as nothing, not as a block of blanks.
		clean = stripTrailingSpace(clean);
		if (clean.isEmpty() || measure == null || maxWidth <= 0) {
			return "";
		}

		boolean truncated = false;
		String capped = limitChars(clean, charLimit);
		if (!capped.equals(clean)) {
			truncated = true;
			// Never leave a dangling space before the dots.
			capped = stripTrailingSpace(capped);
		}

		if (!truncated && measure.applyAsInt(capped) <= maxWidth) {
			return capped;
		}

		int ellipsisWidth = measure.applyAsInt(ELLIPSIS);
		if (ellipsisWidth > maxWidth) {
			// Not even room for the dots: draw nothing rather than bleed over the row.
			return "";
		}
		int budget = maxWidth - ellipsisWidth;
		int end = capped.length();
		while (end > 0) {
			end = capped.offsetByCodePoints(end, -1);
			if (measure.applyAsInt(stripTrailingSpace(capped.substring(0, end))) <= budget) {
				break;
			}
		}
		if (end <= 0) {
			return "";
		}
		String head = stripTrailingSpace(capped.substring(0, end));
		return head.isEmpty() ? "" : head + ELLIPSIS;
	}

	/** Removes trailing ASCII/Latin whitespace without touching other code points. */
	private static String stripTrailingSpace(String text) {
		int end = text.length();
		while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}
		return end == text.length() ? text : text.substring(0, end);
	}

	/**
	 * Convenience overload of {@link #trimToWidth(String, int, ToIntFunction)} that
	 * also reports whether anything was dropped, letting the caller skip building a
	 * styled component for a name it already knows is invisible.
	 */
	public static boolean exceedsWidth(String text, int maxWidth, ToIntFunction<String> measure) {
		if (text == null || text.isEmpty() || measure == null) {
			return false;
		}
		return measure.applyAsInt(text) > maxWidth;
	}

	// ------------------------------------------------------------------
	// Alignment
	// ------------------------------------------------------------------

	/**
	 * Resolves the X coordinate at which {@code textWidth} pixels should be drawn,
	 * given the left edge and total width of the available span.
	 *
	 * <p>Pure integer math, no client-only types, so both the multiplayer list and
	 * the {@code EditBox} preview can share it. If the text is wider than the span
	 * it falls back to {@code baseX} (left-aligned) rather than producing a
	 * negative offset that would clip the name icon.</p>
	 *
	 * @param baseX     left edge of the usable text area
	 * @param spanWidth width of the usable text area in pixels
	 * @param textWidth rendered pixel width of the text
	 * @param alignment one of {@code LEFT}, {@code CENTER}, {@code RIGHT}
	 */
	public static int alignedX(int baseX, int spanWidth, int textWidth, String alignment) {
		if (spanWidth <= 0 || textWidth <= 0) {
			return baseX;
		}
		if (textWidth >= spanWidth) {
			return baseX;
		}
		int free = spanWidth - textWidth;
		return switch (ServerStyle.coerceAlignment(alignment)) {
			case ServerStyle.ALIGN_CENTER -> baseX + free / 2;
			case ServerStyle.ALIGN_RIGHT -> baseX + free;
			default -> baseX;
		};
	}

	/**
	 * Computes the leading pixel padding required to align text of
	 * {@code textWidth} inside {@code spanWidth}, clamped to {@code [0, free]}.
	 * Used to build space-prefix padding for the {@code EditBox} preview.
	 */
	public static int alignedPadding(int spanWidth, int textWidth, String alignment) {
		if (spanWidth <= 0 || textWidth <= 0 || textWidth >= spanWidth) {
			return 0;
		}
		int free = spanWidth - textWidth;
		return switch (ServerStyle.coerceAlignment(alignment)) {
			case ServerStyle.ALIGN_CENTER -> free / 2;
			case ServerStyle.ALIGN_RIGHT -> free;
			default -> 0;
		};
	}

	// ------------------------------------------------------------------
	// Colour maths
	// ------------------------------------------------------------------

	/**
	 * Packs the per-character colours of {@code text} into ARGB ints (alpha
	 * included). Returns an empty array for empty input.
	 */
	public static int[] perCharArgb(String text, ServerStyle style) {
		String clean = prepare(text);
		if (clean.isEmpty()) {
			return new int[0];
		}
		int[] out = new int[clean.length()];
		if (style == null) {
			for (int i = 0; i < out.length; i++) {
				out[i] = ColorUtils.WHITE_ARGB;
			}
			return out;
		}
		boolean gradient = style.isGradient();
		List<String> colors = style.getColors();
		List<Float> positions = style.getPositions();
		float alpha = style.renderAlpha();
		for (int i = 0; i < out.length; i++) {
			out[i] = argbAt(colors, positions, gradient, i, out.length, alpha);
		}
		return out;
	}

	/**
	 * The packed ARGB colour of a single character at {@code index} within a
	 * string of {@code length}. Alpha is always applied as
	 * {@code (alphaByte << 24) | rgb}.
	 */
	public static int argbAt(List<String> colors, boolean gradient, int index, int length,
							 float alpha) {
		return argbAt(colors, null, gradient, index, length, alpha);
	}

	/**
	 * Position-aware variant. {@code positions} may be {@code null}, in which case
	 * the stops are treated as evenly spaced.
	 */
	public static int argbAt(List<String> colors, List<Float> positions, boolean gradient,
							 int index, int length, float alpha) {
		// Single source of truth for alpha quantisation: NaN must collapse to fully
		// opaque here exactly as it does in ColorUtils.withAlpha, otherwise a
		// hand-edited style could render its name completely invisible.
		int alphaByte = ColorUtils.alphaByte(alpha);
		if (colors == null || colors.isEmpty()) {
			return (alphaByte << 24) | 0xFFFFFF;
		}
		if (!gradient || colors.size() == 1) {
			return (alphaByte << 24) | (ColorUtils.parseHex(colors.get(0)) & 0xFFFFFF);
		}
		float t = length <= 1 ? 0.0f : (float) index / (float) (length - 1);
		int rgb = ColorUtils.gradientAt(colors, positions, t, 1.0f) & 0xFFFFFF;
		return (alphaByte << 24) | rgb;
	}

	/**
	 * Folds the alpha channel of a packed ARGB colour into the RGB channels by
	 * compositing it over {@link #LIST_BACKGROUND} (source-over). The result is
	 * opaque and can be stored in a {@link TextColor}.
	 */
	public static int compositeOverBackground(int argb) {
		int alpha = (argb >>> 24) & 0xFF;
		if (alpha >= 0xFF) {
			return argb & 0xFFFFFF;
		}
		if (alpha <= 0) {
			return LIST_BACKGROUND & 0xFFFFFF;
		}
		int src = argb & 0xFFFFFF;
		int dst = LIST_BACKGROUND & 0xFFFFFF;
		float a = alpha / 255.0f;
		int r = Math.round(((src >> 16) & 0xFF) * a + ((dst >> 16) & 0xFF) * (1.0f - a));
		int g = Math.round(((src >> 8) & 0xFF) * a + ((dst >> 8) & 0xFF) * (1.0f - a));
		int b = Math.round((src & 0xFF) * a + (dst & 0xFF) * (1.0f - a));
		r = Math.max(0, Math.min(255, r));
		g = Math.max(0, Math.min(255, g));
		b = Math.max(0, Math.min(255, b));
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * A convenience preview colour: the opaque {@code 0xRRGGBB} value that
	 * represents a solid swatch of {@code rgb} at {@code alpha} over the list
	 * background. Useful for the alpha slider's rendered gradient.
	 */
	public static int previewRgb(int rgb, float alpha) {
		return compositeOverBackground(ColorUtils.withAlpha(alpha, rgb));
	}
}