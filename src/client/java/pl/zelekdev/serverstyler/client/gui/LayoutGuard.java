package pl.zelekdev.serverstyler.client.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime overlap guard for the ServerStyler panels.
 *
 * <p>Its only job is to make sure two things never sit on top of each other.
 * Every panel registers the rectangles it is about to draw, calls
 * {@link #resolve(int, int)}, and then applies the returned Y values. If two
 * rectangles collide, the lower one is pushed down until it clears the one
 * above it; if the result no longer fits inside the panel, the guard reports
 * the overflow instead of silently clipping content.</p>
 *
 * <p>The guard is deliberately free of any Minecraft types: it works on plain
 * integer rectangles, so the same class serves all four version targets and can
 * be unit-tested without a game instance.</p>
 *
 * <p>Repairs are logged once per distinct signature. A panel that is laid out
 * correctly therefore produces no log output at all, and a panel that is not
 * tells you exactly which row moved and by how much.</p>
 */
public final class LayoutGuard {

	private static final Logger LOGGER = LoggerFactory.getLogger("ServerStyler/Layout");

	/** Cap on remembered signatures, so a pathological layout cannot spam memory. */
	private static final int MAX_REPORTS = 64;

	private static final Set<String> REPORTED = new LinkedHashSet<>();

	/**
	 * A named rectangle in GUI-scaled screen coordinates.
	 *
	 * <p>An empty rectangle (zero or negative width/height) is ignored by the
	 * guard: that is how a hidden row is expressed.</p>
	 */
	public record Rect(String name, int x, int y, int w, int h) {

		public int right() {
			return this.x + this.w;
		}

		public int bottom() {
			return this.y + this.h;
		}

		public boolean isEmpty() {
			return this.w <= 0 || this.h <= 0;
		}

		/** True when the two rectangles share at least one pixel. */
		public boolean intersects(Rect other) {
			return this.x < other.right() && other.x < right()
					&& this.y < other.bottom() && other.y < bottom();
		}

		/** The same rectangle moved to a new Y. */
		public Rect at(int newY) {
			return new Rect(this.name, this.x, newY, this.w, this.h);
		}
	}

	/** One repair: a rectangle that had to move, and where it ended up. */
	public record Fix(String name, int fromY, int toY) {

		@Override
		public String toString() {
			return this.name + " " + this.fromY + "->" + this.toY;
		}
	}

	/**
	 * Vertical cursor that hands out row positions one after another.
	 *
	 * <p>{@link #next(int, int)} advances by a gap, returns the row's top, then
	 * advances by the row's height. Because the cursor only ever moves forward,
	 * consecutive rows are guaranteed not to share a pixel - which is what makes
	 * the guard a safety net rather than the primary defence.</p>
	 */
	public static final class Stack {
		private int cursor;

		public Stack(int start) {
			this.cursor = start;
		}

		/** Reserves a row and returns its top edge. */
		public int next(int gap, int height) {
			this.cursor += Math.max(0, gap);
			int top = this.cursor;
			this.cursor += Math.max(0, height);
			return top;
		}

		/** First free Y below everything reserved so far. */
		public int cursor() {
			return this.cursor;
		}
	}

	private final List<Rect> rects = new ArrayList<>();
	private final List<Fix> fixes = new ArrayList<>();
	private boolean overflow;

	/** Registers a rectangle to be checked. */
	public LayoutGuard add(String name, int x, int y, int w, int h) {
		this.rects.add(new Rect(name, x, y, w, h));
		return this;
	}

	/** Registers an already-built rectangle. */
	public LayoutGuard add(Rect rect) {
		if (rect != null) {
			this.rects.add(rect);
		}
		return this;
	}

	/**
	 * Pushes overlapping rectangles apart and clamps everything into
	 * {@code [top, bottom]}.
	 *
	 * <p>Rectangles are processed from the top down, so the visual order of the
	 * panel is preserved: a row only ever moves <em>down</em>, never up past a
	 * row that was already accepted. The returned map holds the final absolute Y
	 * of every registered rectangle, keyed by name.</p>
	 *
	 * @param top    first usable Y (usually just below the header band)
	 * @param bottom last usable Y (the panel's bottom edge)
	 */
	public Map<String, Integer> resolve(int top, int bottom) {
		this.fixes.clear();
		this.overflow = false;

		List<Rect> sorted = new ArrayList<>(this.rects);
		// Stable sort: rectangles sharing a Y keep their registration order, so
		// the left-to-right order of a row is never disturbed.
		sorted.sort(Comparator.comparingInt(Rect::y));

		List<Rect> placed = new ArrayList<>(sorted.size());
		Map<String, Integer> result = new LinkedHashMap<>();

		for (Rect rect : sorted) {
			if (rect.isEmpty()) {
				result.put(rect.name(), rect.y());
				continue;
			}

			int y = Math.max(rect.y(), top);
			boolean moved = y != rect.y();

			// Walk down past every rectangle already accepted. Each pass can only
			// increase y, and the placed list is finite, so this always terminates.
			boolean collided = true;
			while (collided) {
				collided = false;
				for (Rect other : placed) {
					if (other.isEmpty()) {
						continue;
					}
					boolean overlaps = rect.x() < other.right() && other.x() < rect.right()
							&& y < other.bottom() && other.y() < y + rect.h();
					if (overlaps) {
						// Land exactly on the other rectangle's bottom edge: the
						// two then touch without sharing a pixel, which is the
						// tightest legal position.
						y = other.bottom();
						moved = true;
						collided = true;
					}
				}
			}

			if (y + rect.h() > bottom) {
				// Nothing left to push against: the content genuinely does not fit.
				// Clamp so the row stays visible and let report() surface it.
				this.overflow = true;
				y = Math.max(top, bottom - rect.h());
			}

			placed.add(rect.at(y));
			result.put(rect.name(), y);
			if (moved) {
				this.fixes.add(new Fix(rect.name(), rect.y(), y));
			}
		}

		return result;
	}

	/** Repairs applied by the last {@link #resolve(int, int)} call. */
	public List<Fix> fixes() {
		return List.copyOf(this.fixes);
	}

	/** True when the last resolve had to clamp content that did not fit. */
	public boolean overflowed() {
		return this.overflow;
	}

	/** True when the last resolve changed nothing. */
	public boolean isClean() {
		return this.fixes.isEmpty() && !this.overflow;
	}

	/**
	 * Logs the last resolve, once per distinct signature.
	 *
	 * @param owner short label naming the panel, used as the log prefix
	 */
	public void report(String owner) {
		if (isClean()) {
			return;
		}
		StringBuilder message = new StringBuilder(owner).append(": layout repaired");
		if (this.overflow) {
			message.append(" (content overflows the panel)");
		}
		message.append(" - ");
		for (int i = 0; i < this.fixes.size(); i++) {
			if (i > 0) {
				message.append(", ");
			}
			message.append(this.fixes.get(i));
		}
		String signature = message.toString();
		if (REPORTED.size() < MAX_REPORTS && REPORTED.add(signature)) {
			LOGGER.warn(signature);
		}
	}

	/** Clears the remembered signatures; used by tests. */
	public static void resetReports() {
		REPORTED.clear();
	}
}
