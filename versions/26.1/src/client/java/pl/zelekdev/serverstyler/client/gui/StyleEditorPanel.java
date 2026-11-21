package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.config.ColorUtils;
import pl.zelekdev.serverstyler.config.ServerStyle;
import pl.zelekdev.serverstyler.i18n.I18n;
import pl.zelekdev.serverstyler.i18n.Keys;
import pl.zelekdev.serverstyler.render.TextStylingEngine;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * The floating style editor injected next to the "Server Name" field of
 * {@code ManageServerScreen} (26.1+ extract/render-state API).
 *
 * <p>It is itself an {@link AbstractWidget}, registered on the screen
 * <em>before</em> its child widgets, so its backdrop renders underneath them
 * (screens draw renderables in insertion order).</p>
 *
 * <h3>Visual design</h3>
 * <p>Built entirely from primitives the game already owns &mdash; black wells,
 * one-pixel light edges, native button sprites &mdash; so the panel reads as part of
 * Minecraft rather than as an overlay bolted on top. The three control groups sit
 * in their own recessed cards, so the panel reads as separate blocks instead of
 * one dense column:</p>
 * <ol>
 *   <li><b>Preview</b> &mdash; the finished name, drawn exactly as the multiplayer
 *       list will draw it, inside the same dark well the list uses.</li>
 *   <li><b>Colour</b> &mdash; the gradient bar with draggable stop handles, a hue
 *       ramp, an opacity ramp, the {@code #RRGGBB} field and the recent-colour
 *       strip.</li>
 *   <li><b>Style</b> &mdash; solid/gradient, bold, italic, mini caps.</li>
 *   <li><b>Layout</b> &mdash; left / centre / right alignment.</li>
 * </ol>
 *
 * <h3>Positioning contract</h3>
 * <p>All child coordinates are relative to the panel origin, and the panel origin
 * is derived strictly from the live name-field bounds plus the current GUI-scaled
 * screen size:</p>
 * <ul>
 *   <li>Trigger: {@code nameEdit.x + nameEdit.width + 4}, vertically at
 *       {@code nameEdit.y}.</li>
 *   <li>Panel: immediately right of the trigger, top-aligned with
 *       {@code nameEdit.y}.</li>
 *   <li>If the panel would overflow the right screen edge it is mirrored to the
 *       left of the name field.</li>
 * </ul>
 * <p>No absolute pixels, no hardcoded origins. Everything is rebuilt from the
 * anchor on every frame ({@link #relayout()}), so GUI Scale changes and window
 * resizes are handled without any screen re-init.</p>
 */
public final class StyleEditorPanel extends AbstractWidget {

	/** Panel width in GUI-scaled pixels. */
	public static final int WIDTH = 180;

	/** Height of the full panel (GUI scale 1&ndash;2). */
	public static final int HEIGHT = 262;

	/** Height of the compact panel (GUI scale 3). */
	public static final int COMPACT_HEIGHT = 184;

	/** Height of the tiny panel (GUI scale 3 on a small window). */
	public static final int TINY_HEIGHT = 148;

	/** Gap between the name field and the trigger button. */
	public static final int TRIGGER_GAP = 4;
	/** Gap between the trigger button and the panel's left edge. */
	public static final int PANEL_GAP = 4;
	/** Trigger button edge length, in GUI-scaled pixels. */
	public static final int TRIGGER_SIZE = 20;
	/** Space reserved right of the field for trigger + clearance. */
	public static final int GAP = TRIGGER_GAP + TRIGGER_SIZE + PANEL_GAP;
	/** Smallest distance kept from any screen edge. */
	public static final int MARGIN = 4;

	private static final int MAX_STOPS = ServerStyle.MAX_GRADIENT_STOPS;

	/** Layout tiers, ordered from roomiest to tightest. */
	public static final int MODE_FULL = 0;
	public static final int MODE_COMPACT = 1;
	public static final int MODE_TINY = 2;

	// ---- Fixed geometry ------------------------------------------------------

	/** Inner content margin, left and right. */
	private static final int PAD = 8;
	/** Content width: the space every full-width row spans. */
	private static final int INNER = WIDTH - PAD * 2;
	/** Thirds of {@link #INNER}, leaving 3 px gaps between the three cells. */
	private static final int THIRD_W = (INNER - 6) / 3;

	/** Half-width of a stop handle marker, centred on the stop position. */
	private static final int HANDLE_W_HALF = 4;

	/**
	 * Row height used only for the widgets' <em>initial</em> bounds at construction
	 * time; {@link #layout(int, int)} overwrites every one of them with the active
	 * mode's {@link #rowH}.
	 */
	private static final int ROW_H = 16;

	// ---- Recent-colour strip ------------------------------------------------

	/** Number of remembered colours. */
	private static final int MAX_RECENT = 8;
	/** Width of one recent-colour swatch, in GUI-scaled pixels. */
	private static final int RECENT_SWATCH_W = 19;
	/** Gap between two swatches. */
	private static final int RECENT_SWATCH_GAP = 1;
	/** Total width of the swatch strip. */
	private static final int RECENT_STRIP_W =
			MAX_RECENT * (RECENT_SWATCH_W + RECENT_SWATCH_GAP) - RECENT_SWATCH_GAP;

	// ---- Active layout metrics (set by applyMode) ----------------------------
	//
	// These used to be `static final` constants. They are instance fields now so
	// the panel can switch between a full and a compact geometry at runtime: at
	// GUI scale 3 the usable screen is only ~640x360 GUI pixels, and a 254 px
	// panel leaves no room for the preset column at all.

	private boolean compact;
	private int mode;
	private int height;
	private int rowH;
	private int headerH;
	private int previewY;
	private int previewH;
	private int historyY;
	private int colourLabelY;
	private int barY;
	private int barH;
	private int handleHalf;
	private int hueY;
	private int alphaY;
	private int hexY;
	private int recentY;
	private int recentH;
	private int styleLabelY;
	private int modeY;
	private int formatY;
	private int layoutLabelY;
	private int alignY;

	private final ServerStyle style;
	private final Font font;
	private final Anchor anchor;
	private Consumer<AbstractWidget> widgetSink;

	private HorizontalHueSlider hueSlider;
	private HorizontalAlphaSlider alphaSlider;
	private EditBox hexBox;
	private Button modeButton;
	private Button boldButton;
	private Button italicButton;
	private Button enabledButton;
	private Button addStopButton;
	private Button removeStopButton;
	private Button alignLeftButton;
	private Button alignCenterButton;
	private Button alignRightButton;
	private Button miniCapsButton;
	private Button undoButton;
	private Button redoButton;
	private Button resetButton;

	/** Index of the stop being edited, or {@code -1} when none is selected. */
	private int activeStop;
	/** Index of the stop handle currently being dragged, or {@code -1}. */
	private int draggingStop = -1;
	private boolean updating;
	private boolean modified;
	/** Whether the user asked for the panel to be open. */
	private boolean requestedVisible;
	/** True once the anchor produced a sane field position + screen size. */
	private boolean dimsValid;

	// ---- Recent colours ------------------------------------------------------

	/**
	 * Recently committed colours, most recent first, as {@code #RRGGBB}.
	 *
	 * <p>Kept in memory only: this is a convenience palette for the current
	 * session, not part of the saved style, so it must never touch the config
	 * file.</p>
	 */
	private final List<String> recentColors = new ArrayList<>(MAX_RECENT);

	// ---- Undo / redo ---------------------------------------------------------

	/** How many history entries are kept. */
	private static final int MAX_HISTORY = 20;

	/** Snapshots of the style taken <em>before</em> each edit, newest last. */
	private final Deque<ServerStyle> undoStack = new ArrayDeque<>(MAX_HISTORY);

	/** Snapshots undone by the user, newest last, cleared by any fresh edit. */
	private final Deque<ServerStyle> redoStack = new ArrayDeque<>(MAX_HISTORY);

	/** True while undo/redo is restoring, so the restore does not record itself. */
	private boolean restoring;

	/**
	 * The style as it looked after the last completed edit.
	 *
	 * <p>Undo needs the state <em>before</em> a change, but some changes arrive
	 * from outside this class (a preset applied by the shelf). Keeping a rolling
	 * snapshot means history can be recorded at any point without every caller
	 * having to remember to capture the old value first.</p>
	 */
	private ServerStyle lastSnapshot;

	/** Wall-clock time of the last commit, used to coalesce drag gestures. */
	private long lastCommitMillis;

	/** True while a slider press-drag-release gesture is in progress. */
	private boolean inGesture;

	/** The style as it was when the current gesture started. */
	private ServerStyle gestureSnapshot;

	public StyleEditorPanel(ServerStyle style, Font font, Anchor anchor) {
		super(0, 0, WIDTH, HEIGHT, Component.literal(I18n.tr(Keys.PANEL_TITLE)));
		this.style = style;
		this.font = font;
		this.anchor = anchor;
		// Backdrop + labels only - the panel must never capture input itself.
		this.active = false;
		this.visible = false;
		applyMode(MODE_FULL);
		// Baseline for the first undo step: the style as it was when the screen
		// opened, so the very first edit can be reverted.
		this.lastSnapshot = style.copy();
	}

	/**
	 * Switches between the full, compact and tiny geometries.
	 *
	 * <p>Compact mode exists for GUI scale 3+: the whole screen is then only about
	 * 640&times;360 GUI pixels, so a 254 px panel would leave no room for the preset
	 * column and would cover the server form. Tiny mode is the last resort for a
	 * small window at GUI scale 3 (e.g. 1366&times;768 &rarr; 455&times;256 GUI
	 * pixels), where even the compact panel plus the preset column would not fit.</p>
	 */
	private void applyMode(int mode) {
		this.mode = mode;
		this.compact = mode != MODE_FULL;
		if (mode == MODE_TINY) {
			this.height = TINY_HEIGHT;
			this.rowH = 10;
			this.headerH = 11;
			// No preview strip in the tiny tier: the space is needed for the
			// dedicated history and recent-colour rows.
			this.previewY = 11;
			this.previewH = 0;
			this.historyY = 12;
			this.colourLabelY = 23;
			this.barY = 33;
			this.barH = 8;
			// Handles sit inside the bar here: a 4 px overhang would collide with
			// the COLOUR label above and the hue ramp below.
			this.handleHalf = 0;
			this.hueY = 44;
			this.alphaY = 56;
			this.hexY = 68;
			this.recentY = 80;
			this.recentH = 8;
			this.styleLabelY = 92;
			this.modeY = 101;
			this.formatY = 113;
			this.layoutLabelY = 127;
			this.alignY = 136;
		} else if (mode == MODE_COMPACT) {
			this.height = COMPACT_HEIGHT;
			this.rowH = 11;
			this.headerH = 12;
			this.previewY = 13;
			this.previewH = 12;
			this.historyY = 27;
			this.colourLabelY = 42;
			this.barY = 54;
			this.barH = 9;
			this.handleHalf = 3;
			this.hueY = 67;
			this.alphaY = 80;
			this.hexY = 93;
			this.recentY = 106;
			this.recentH = 9;
			this.styleLabelY = 119;
			this.modeY = 129;
			this.formatY = 143;
			this.layoutLabelY = 158;
			this.alignY = 168;
		} else {
			this.height = HEIGHT;
			this.rowH = 16;
			this.headerH = 18;
			this.previewY = 19;
			this.previewH = 20;
			this.historyY = 42;
			this.colourLabelY = 64;
			this.barY = 78;
			this.barH = 14;
			this.handleHalf = 4;
			this.hueY = 98;
			this.alphaY = 118;
			this.hexY = 138;
			this.recentY = 158;
			this.recentH = 12;
			this.styleLabelY = 176;
			this.modeY = 188;
			this.formatY = 208;
			this.layoutLabelY = 230;
			this.alignY = 242;
		}
	}

	/** Height of the panel in its current mode. */
	public int height() {
		return this.height;
	}

	/** True when the panel is currently drawn in its compact geometry. */
	public boolean isCompact() {
		return this.compact;
	}

	/**
	 * Creates and registers the panel's child widgets. The host screen calls this
	 * <em>after</em> the panel itself has been added with
	 * {@code addRenderableWidget(panel)}, so the backdrop draws beneath them.
	 */
	public void addChildren(Consumer<AbstractWidget> sink) {
		this.widgetSink = sink;
		createColourControls();
		createStyleControls();
		createLayoutControls();
		createHistoryControls();
		syncFromStyle();
		relayout();
	}

	// ------------------------------------------------------------------
	// Placement (GUI-scaled, derived strictly from nameEdit + screen size)
	// ------------------------------------------------------------------

	/**
	 * Live anchor. Implementations must read the current name-field bounds and the
	 * GUI-scaled screen size <em>on every call</em>, so the panel always snaps to
	 * the field even after a window resize or GUI-scale change.
	 */
	public interface Anchor {
		int fieldLeft();

		int fieldRight();

		int fieldTop();

		int screenWidth();

		int screenHeight();
	}

	/**
	 * Recomputes the panel origin from the live anchor and lays out the children.
	 * Safe to call every frame. Until the window is sized it keeps the panel hidden
	 * so it can never be drawn at a bogus {@code (0, 0)} origin.
	 *
	 * <p>The panel also picks its geometry here: the full 254 px layout is used
	 * whenever the preset column can still be placed next to or under it, and the
	 * compact 180 px layout otherwise. That is what makes the whole editor usable
	 * at GUI scale 3, where the screen is only ~640&times;360 GUI pixels.</p>
	 */
	public void relayout() {
		int screenWidth = this.anchor.screenWidth();
		int screenHeight = this.anchor.screenHeight();
		int fieldRight = this.anchor.fieldRight();
		if (screenWidth <= WIDTH || screenHeight < TINY_HEIGHT + MARGIN * 2 || fieldRight <= 0) {
			this.dimsValid = false;
			applyVisibility();
			return;
		}

		// Pick the roomiest geometry that still leaves a home for the preset
		// column. Tiny is used unconditionally as the last resort so the editor
		// itself is always reachable, even on a small window at GUI scale 3.
		int chosen = MODE_TINY;
		for (int candidate = MODE_FULL; candidate <= MODE_COMPACT; candidate++) {
			if (modeFits(candidate, screenWidth, screenHeight)) {
				chosen = candidate;
				break;
			}
		}
		applyMode(chosen);
		this.dimsValid = true;

		int x = panelX(this.anchor.fieldLeft(), fieldRight, screenWidth);
		int y = panelY(this.anchor.fieldTop(), screenHeight, this.height);

		// The preset column has to fit underneath. If the editor is sitting too
		// low for that, lift it to the top margin: at GUI scale 3 the screen is
		// only ~360 GUI pixels tall, and a column that silently vanishes is worse
		// than an editor that is not top-aligned with the name field.
		if (this.compact
				&& y + this.height + GAP + PresetPanel.TINY_HEIGHT > screenHeight - MARGIN) {
			y = MARGIN;
		}

		layout(x, y);
	}

	/**
	 * True when the editor in {@code mode} still leaves room for the preset column,
	 * either to its right or below it.
	 */
	private boolean modeFits(int mode, int screenWidth, int screenHeight) {
		int h = heightForMode(mode);
		int x = panelX(this.anchor.fieldLeft(), this.anchor.fieldRight(), screenWidth);
		int y = panelY(this.anchor.fieldTop(), screenHeight, h);

		// The panel itself must always fit on screen, whatever the preset column
		// does - otherwise the bottom rows would be clipped away.
		if (y + h + MARGIN > screenHeight) {
			return false;
		}

		// Right of the editor: the column only has to fit vertically, because
		// tryPlaceRight() clamps its Y into the screen.
		if (x + WIDTH + GAP + PresetPanel.WIDTH + MARGIN <= screenWidth
				&& PresetPanel.COMPACT_HEIGHT + MARGIN * 2 <= screenHeight) {
			return true;
		}
		// Below the editor.
		return y + h + GAP + PresetPanel.COMPACT_HEIGHT + MARGIN <= screenHeight;
	}

	/** Height the editor would have in {@code mode}, without switching to it. */
	private static int heightForMode(int mode) {
		if (mode == MODE_TINY) {
			return TINY_HEIGHT;
		}
		if (mode == MODE_COMPACT) {
			return COMPACT_HEIGHT;
		}
		return HEIGHT;
	}

	/**
	 * X of the panel. Anchored directly to the right of the trigger button; if that
	 * overflows the screen it is mirrored to the left of the name field, and only
	 * as a last resort clamped inside the screen.
	 */
	public static int panelX(int fieldLeft, int fieldRight, int screenWidth) {
		int preferred = fieldRight + GAP;
		if (preferred + WIDTH + MARGIN <= screenWidth) {
			return preferred;
		}
		int mirrored = fieldLeft - GAP - WIDTH;
		if (mirrored >= MARGIN) {
			return mirrored;
		}
		return clamp(preferred, MARGIN, screenWidth - WIDTH - MARGIN);
	}

	/** Y of the panel: top-aligned with the name field, kept on screen. */
	public static int panelY(int fieldTop, int screenHeight, int panelHeight) {
		return clamp(fieldTop, MARGIN, screenHeight - panelHeight - MARGIN);
	}

	/** X of the trigger button: immediately right of the name field. */
	public static int triggerX(int fieldRight, int screenWidth) {
		return clamp(fieldRight + TRIGGER_GAP, MARGIN, screenWidth - TRIGGER_SIZE - MARGIN);
	}

	/** Y of the trigger button: aligned with the name field. */
	public static int triggerY(int fieldTop, int screenHeight) {
		return clamp(fieldTop, MARGIN, screenHeight - TRIGGER_SIZE - MARGIN);
	}

	private static int clamp(int value, int min, int max) {
		return max < min ? min : Math.max(min, Math.min(max, value));
	}

	// ------------------------------------------------------------------
	// Widget construction
	// ------------------------------------------------------------------

	private void createColourControls() {
		// A single hue ramp drives whichever stop is selected. Stacking one ramp per
		// stop made the panel grow with the gradient; chips plus one ramp keeps the
		// height fixed and the interaction identical to a colour picker.
		this.hueSlider = new HorizontalHueSlider(this.font, 0, 0, INNER, ROW_H, 0.0f, hue -> {
			if (this.updating) {
				return;
			}
			this.style.setColor(this.activeStop, ColorUtils.toHex(applyHue(this.activeStop, hue)));
			commit();
			syncDerivedFromColor(false);
		});
		this.hueSlider.setGestureHooks(this::beginGesture, this::endGesture);
		this.widgetSink.accept(this.hueSlider);

		this.alphaSlider = new HorizontalAlphaSlider(this.font, 0, 0, INNER, ROW_H, 1.0f,
				() -> this.style.getPrimaryRgb() & 0xFFFFFF, alpha -> {
			if (this.updating) {
				return;
			}
			this.style.setOpacity((float) alpha);
			commit();
		});
		this.alphaSlider.setGestureHooks(this::beginGesture, this::endGesture);
		this.widgetSink.accept(this.alphaSlider);

		this.hexBox = new EditBox(this.font, 0, 0, INNER, ROW_H, Component.literal(I18n.tr(Keys.HEX_LABEL)));
		this.hexBox.setMaxLength(7);
		this.hexBox.setResponder(this::onHexTyped);
		this.hexBox.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.HEX_TOOLTIP))));
		this.widgetSink.accept(this.hexBox);
	}

	private void createStyleControls() {
		this.modeButton = Button.builder(Component.literal(I18n.tr(Keys.MODE_LABEL)), b -> toggleMode())
				.bounds(0, 0, INNER, ROW_H).build();
		this.modeButton.setTooltip(Tooltip.create(
				Component.literal(I18n.tr(Keys.MODE_TOOLTIP))));
		this.widgetSink.accept(this.modeButton);

		this.boldButton = Button.builder(Component.literal(I18n.tr(Keys.BOLD_LABEL)), b -> {
			this.style.setBold(!this.style.isBold());
			commit();
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.boldButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.BOLD_TOOLTIP))));
		this.widgetSink.accept(this.boldButton);

		this.italicButton = Button.builder(Component.literal(I18n.tr(Keys.ITALIC_LABEL)), b -> {
			this.style.setItalic(!this.style.isItalic());
			commit();
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.italicButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.ITALIC_TOOLTIP))));
		this.widgetSink.accept(this.italicButton);

		this.miniCapsButton = Button.builder(
				Component.literal(TextStylingEngine.toMiniCaps("Aa")), b -> {
					this.style.setMiniCaps(!this.style.isMiniCaps());
					commit();
					refreshLabels();
				}).bounds(0, 0, INNER - THIRD_W * 2 - 6, ROW_H).build();
		this.miniCapsButton.setTooltip(Tooltip.create(Component.literal(
				I18n.tr(Keys.MINICAPS_TOOLTIP))));
		this.widgetSink.accept(this.miniCapsButton);

		// On/off switch lives in the header: it is the most important toggle and
		// deserves permanent prominence rather than a slot in a list.
		this.enabledButton = Button.builder(Component.literal(I18n.tr(Keys.ENABLED_ON)), b -> {
			this.style.setEnabled(!this.style.isEnabled());
			commit();
			refreshLabels();
		}).bounds(0, 0, 34, 12).build();
		this.enabledButton.setTooltip(Tooltip.create(Component.literal(
				I18n.tr(Keys.ENABLED_TOOLTIP))));
		this.widgetSink.accept(this.enabledButton);

		this.addStopButton = Button.builder(Component.literal(I18n.tr(Keys.STOP_ADD_LABEL)), b -> {
			// Remember the spacing before the insert so the newly added stop can be
			// identified afterwards: the new one is the index whose position is not
			// present in the old list. New stops are inserted into the widest gap, not
			// appended, so counting alone would select the wrong stop.
			List<Float> before = this.style.getPositions();
			this.style.setMode(ServerStyle.MODE_GRADIENT);
			this.style.addGradientStop();
			this.activeStop = findInsertedStop(before);
			commit();
			syncFromStyle();
		}).bounds(0, 0, 13, 11).build();
		this.addStopButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.STOP_ADD_TOOLTIP))));
		this.widgetSink.accept(this.addStopButton);

		this.removeStopButton = Button.builder(Component.literal(I18n.tr(Keys.STOP_REMOVE_LABEL)), b -> {
			// Remove the currently selected stop when it is removable, otherwise the
			// last interior one - so the button always does something predictable.
			int target = this.activeStop;
			if (!this.style.isPositionMovable(target)) {
				target = this.style.gradientStopCount() - 2;
			}
			this.style.removeGradientStopAt(target);
			this.activeStop = Math.max(0, Math.min(this.activeStop, this.style.gradientStopCount() - 1));
			commit();
			syncFromStyle();
		}).bounds(0, 0, 13, 11).build();
		this.removeStopButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.STOP_REMOVE_TOOLTIP))));
		this.widgetSink.accept(this.removeStopButton);
	}

	/**
	 * Finds the stop that appeared after an insert, comparing the current positions
	 * against the list captured beforehand.
	 *
	 * <p>Inserting into the widest gap shifts later indices, so the new stop cannot
	 * be assumed to be the last one. Matching on position is exact because
	 * {@link ServerStyle#normalisePositions()} never rewrites an existing interior
	 * position unless it collides with a neighbour.</p>
	 */
	private int findInsertedStop(List<Float> before) {
		List<Float> now = this.style.getPositions();
		for (int i = 0; i < now.size(); i++) {
			boolean found = false;
			for (float old : before) {
				if (Math.abs(now.get(i) - old) < 0.0001f) {
					found = true;
					break;
				}
			}
			if (!found) {
				return i;
			}
		}
		// Fallback: the last stop is always a safe, non-crashing choice.
		return Math.max(0, now.size() - 1);
	}

	private void createLayoutControls() {
		this.alignLeftButton = Button.builder(Component.literal(I18n.tr(Keys.ALIGN_LEFT_LABEL)), b -> {
			this.style.setAlignment(ServerStyle.ALIGN_LEFT);
			commit();
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignLeftButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.ALIGN_LEFT_TOOLTIP))));
		this.widgetSink.accept(this.alignLeftButton);

		this.alignCenterButton = Button.builder(Component.literal(I18n.tr(Keys.ALIGN_CENTER_LABEL)), b -> {
			this.style.setAlignment(ServerStyle.ALIGN_CENTER);
			commit();
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignCenterButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.ALIGN_CENTER_TOOLTIP))));
		this.widgetSink.accept(this.alignCenterButton);

		this.alignRightButton = Button.builder(Component.literal(I18n.tr(Keys.ALIGN_RIGHT_LABEL)), b -> {
			this.style.setAlignment(ServerStyle.ALIGN_RIGHT);
			commit();
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignRightButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.ALIGN_RIGHT_TOOLTIP))));
		this.widgetSink.accept(this.alignRightButton);
	}

	/**
	 * Undo, redo and reset.
	 *
	 * <p>They get a full-width row of their own directly under the preview, so the
	 * STYLE label row is left to the label and its hairline instead of being
	 * crowded with three buttons.</p>
	 */
	private void createHistoryControls() {
		this.undoButton = Button.builder(Component.literal(I18n.tr(Keys.UNDO_LABEL)), b -> undo())
				.bounds(0, 0, THIRD_W, ROW_H).build();
		this.undoButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.UNDO_TOOLTIP))));
		this.widgetSink.accept(this.undoButton);

		this.redoButton = Button.builder(Component.literal(I18n.tr(Keys.REDO_LABEL)), b -> redo())
				.bounds(0, 0, THIRD_W, ROW_H).build();
		this.redoButton.setTooltip(Tooltip.create(Component.literal(I18n.tr(Keys.REDO_TOOLTIP))));
		this.widgetSink.accept(this.redoButton);

		this.resetButton = Button.builder(Component.literal(I18n.tr(Keys.RESET_LABEL)), b -> resetStyle())
				.bounds(0, 0, INNER - THIRD_W * 2 - 6, ROW_H).build();
		this.resetButton.setTooltip(Tooltip.create(Component.literal(
				I18n.tr(Keys.RESET_TOOLTIP))));
		this.widgetSink.accept(this.resetButton);
	}

	// ------------------------------------------------------------------
	// State exposed to the host mixin
	// ------------------------------------------------------------------

	public ServerStyle style() {
		return this.style;
	}

	/** True while the user wants the panel open (independent of layout readiness). */
	public boolean isVisible() {
		return this.requestedVisible;
	}

	/** True once the user actually changed something (decides whether to persist). */
	public boolean isModified() {
		return this.modified;
	}

	/**
	 * Flags the working style as edited.
	 *
	 * <p>Used by the preset shelf: applying a preset replaces the style wholesale,
	 * which is an edit the user made, so the host screen must persist it on Done.</p>
	 */
	public void markModified() {
		this.modified = true;
	}

	/**
	 * Called by the preset shelf after it has merged a preset into the working
	 * style.
	 *
	 * <p>Records the pre-preset state on the undo stack, then re-syncs every
	 * widget. Without this an applied preset would be invisible to undo, which is
	 * exactly the case where a mis-click is most costly.</p>
	 */
	public void onStyleReplaced() {
		commit();
		syncFromStyle();
	}

	// ------------------------------------------------------------------
	// Undo / redo / reset
	// ------------------------------------------------------------------

	/**
	 * Records the state that existed <em>before</em> the change just made.
	 *
	 * <p>Called after every mutation. {@link #lastSnapshot} always holds the style
	 * as it was after the previous commit, so pushing it here captures exactly one
	 * undo step per user action, regardless of how many fields that action
	 * touched.</p>
	 */
	private void commit() {
		if (this.restoring) {
			return;
		}
		if (this.inGesture) {
			// A slider is being held: the whole press-drag-release becomes one undo
			// entry, pushed by endGesture() on release. Recording here would fill the
			// history with one entry per pixel of the drag.
			this.modified = true;
			return;
		}
		if (this.lastSnapshot != null) {
			this.undoStack.push(this.lastSnapshot);
			while (this.undoStack.size() > MAX_HISTORY) {
				this.undoStack.removeLast();
			}
		}
		this.redoStack.clear();
		this.lastSnapshot = this.style.copy();
		this.modified = true;
		refreshHistoryButtons();
	}

	/**
	 * Opens a gesture: remembers the style as it is right now.
	 *
	 * <p>Called when a slider is pressed. Every {@link #commit()} until
	 * {@link #endGesture()} is a no-op for the history, so undo jumps back to the
	 * value the slider had when it was grabbed.</p>
	 */
	private void beginGesture() {
		if (this.inGesture) {
			return;
		}
		this.inGesture = true;
		this.gestureSnapshot = this.style.copy();
	}

	/** Closes a gesture, pushing its single undo entry. */
	private void endGesture() {
		if (!this.inGesture) {
			return;
		}
		this.inGesture = false;
		ServerStyle before = this.gestureSnapshot;
		this.gestureSnapshot = null;
		if (before == null) {
			return;
		}
		this.undoStack.push(before);
		while (this.undoStack.size() > MAX_HISTORY) {
			this.undoStack.removeLast();
		}
		this.redoStack.clear();
		this.lastSnapshot = this.style.copy();
		this.modified = true;
		refreshHistoryButtons();
	}

	/** Restores the previous snapshot, if any. */
	private void undo() {
		if (this.undoStack.isEmpty()) {
			return;
		}
		this.redoStack.push(this.style.copy());
		while (this.redoStack.size() > MAX_HISTORY) {
			this.redoStack.removeLast();
		}
		ServerStyle previous = this.undoStack.pop();
		restore(previous);
	}

	/** Re-applies the snapshot most recently undone. */
	private void redo() {
		if (this.redoStack.isEmpty()) {
			return;
		}
		this.undoStack.push(this.style.copy());
		while (this.undoStack.size() > MAX_HISTORY) {
			this.undoStack.removeLast();
		}
		ServerStyle next = this.redoStack.pop();
		restore(next);
	}

	/**
	 * Merges a snapshot into the live style without recording history.
	 *
	 * <p>{@code copyFrom} is used rather than replacing the reference: the host
	 * screen persists this exact instance.</p>
	 */
	private void restore(ServerStyle snapshot) {
		this.restoring = true;
		try {
			this.style.copyFrom(snapshot);
			this.lastSnapshot = this.style.copy();
			this.activeStop = Math.max(0, Math.min(this.activeStop, activeStopCount() - 1));
			this.modified = true;
			syncFromStyle();
		} finally {
			this.restoring = false;
		}
		refreshHistoryButtons();
	}

	/** Clears the style back to a fresh default, as one undoable step. */
	private void resetStyle() {
		this.style.copyFrom(new ServerStyle());
		this.activeStop = 0;
		commit();
		syncFromStyle();
	}

	private void refreshHistoryButtons() {
		if (this.undoButton != null) {
			this.undoButton.active = this.visible && !this.undoStack.isEmpty();
		}
		if (this.redoButton != null) {
			this.redoButton.active = this.visible && !this.redoStack.isEmpty();
		}
	}

	// ------------------------------------------------------------------
	// Recent colours
	// ------------------------------------------------------------------

	/**
	 * Remembers a colour the user just committed, most recent first.
	 *
	 * <p>Duplicates are moved to the front rather than added again, so the palette
	 * stays a short list of distinct colours instead of filling with repeats.</p>
	 */
	private void rememberColor(String hex) {
		if (hex == null || hex.isEmpty()) {
			return;
		}
		String clean = ColorUtils.sanitiseHex(hex);
		this.recentColors.remove(clean);
		this.recentColors.add(0, clean);
		while (this.recentColors.size() > MAX_RECENT) {
			this.recentColors.remove(this.recentColors.size() - 1);
		}
	}

	/** Applies a colour from the recent palette to the selected stop. */
	private void applyRecentColor(String hex) {
		this.style.setColor(this.activeStop, hex);
		commit();
		syncFromStyle();
	}

	/** Number of swatches currently in the recent palette. */
	private int recentColorCount() {
		return this.recentColors.size();
	}

	public void setVisible(boolean visible) {
		this.requestedVisible = visible;
		this.active = false;
		clearDrag();
		applyVisibility();
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	/**
	 * Positions the panel and every child widget relative to {@code (originX, originY)}
	 * -- the panel's top-left corner, in GUI-scaled screen coordinates.
	 *
	 * <p>Every row has a fixed Y constant, so the panel height never changes with
	 * the number of gradient stops. The alternative (stacking one hue ramp per
	 * stop) made the panel grow taller than the screen at GUI scale 4.</p>
	 */
	public void layout(int originX, int originY) {
		place(this, originX, originY, WIDTH, this.height);

		int col = originX + PAD;

		place(this.hueSlider, col, originY + this.hueY, INNER, this.rowH);
		place(this.alphaSlider, col, originY + this.alphaY, INNER, this.rowH);
		place(this.hexBox, col, originY + this.hexY, INNER, this.rowH);

		place(this.modeButton, col, originY + this.modeY, INNER, this.rowH);
		place(this.boldButton, col, originY + this.formatY, THIRD_W, this.rowH);
		place(this.italicButton, col + THIRD_W + 3, originY + this.formatY, THIRD_W, this.rowH);
		place(this.miniCapsButton, col + (THIRD_W + 3) * 2, originY + this.formatY,
				INNER - THIRD_W * 2 - 6, this.rowH);

		place(this.alignLeftButton, col, originY + this.alignY, THIRD_W, this.rowH);
		place(this.alignCenterButton, col + THIRD_W + 3, originY + this.alignY, THIRD_W, this.rowH);
		place(this.alignRightButton, col + (THIRD_W + 3) * 2, originY + this.alignY,
				INNER - THIRD_W * 2 - 6, this.rowH);

		// Header switch, vertically centred in the header band.
		int switchH = this.mode == MODE_FULL ? 12 : (this.mode == MODE_TINY ? 9 : 10);
		place(this.enabledButton, originX + WIDTH - PAD - 34,
				originY + (this.headerH - switchH) / 2, 34, switchH);

		// Stop add/remove, right-aligned in the COLOUR label row.
		int stopH = this.mode == MODE_FULL ? 10 : (this.mode == MODE_TINY ? 8 : 9);
		place(this.addStopButton, originX + WIDTH - PAD - 28, originY + this.colourLabelY - 1, 13, stopH);
		place(this.removeStopButton, originX + WIDTH - PAD - 13, originY + this.colourLabelY - 1, 13, stopH);

		// Undo / redo / reset: their own full-width row under the preview, so the
		// STYLE label row is left to the label alone.
		int histY = originY + this.historyY;
		place(this.undoButton, col, histY, THIRD_W, this.rowH);
		place(this.redoButton, col + THIRD_W + 3, histY, THIRD_W, this.rowH);
		place(this.resetButton, col + (THIRD_W + 3) * 2, histY, INNER - THIRD_W * 2 - 6, this.rowH);

		applyVisibility();
	}

	/**
	 * Positions a widget explicitly.
	 *
	 * <p>Note: vanilla's {@code AbstractWidget.setRectangle} is
	 * {@code (width, height, x, y)} - <em>not</em> {@code (x, y, width, height)}.
	 * These four setters remove that ambiguity entirely.</p>
	 */
	private static void place(AbstractWidget widget, int x, int y, int width, int height) {
		if (widget == null) {
			return;
		}
		widget.setX(x);
		widget.setY(y);
		widget.setWidth(width);
		widget.setHeight(height);
	}

	private void applyVisibility() {
		// The panel and all its children are only shown once the anchor has produced
		// a sane position - this prevents the "rendered at 0,0" bug.
		boolean shown = this.requestedVisible && this.dimsValid;
		this.visible = shown;
		this.active = false;

		boolean gradient = this.style.isGradient();
		int stops = this.style.gradientStopCount();

		show(this.hueSlider, shown);
		show(this.alphaSlider, shown);
		show(this.hexBox, shown);
		show(this.modeButton, shown);
		show(this.boldButton, shown);
		show(this.italicButton, shown);
		show(this.miniCapsButton, shown);
		show(this.enabledButton, shown);
		show(this.alignLeftButton, shown);
		show(this.alignCenterButton, shown);
		show(this.alignRightButton, shown);
		show(this.addStopButton, shown && gradient && stops < MAX_STOPS);
		show(this.removeStopButton, shown && gradient && stops > 2);
		show(this.undoButton, shown);
		show(this.redoButton, shown);
		show(this.resetButton, shown);
		refreshHistoryButtons();

		// The stop chips and section labels are painted by this widget, so they need
		// the panel itself visible - already handled above.
		if (!shown) {
			this.activeStop = Math.min(this.activeStop, Math.max(0, stops - 1));
		}
	}

	private static void show(AbstractWidget widget, boolean visible) {
		if (widget == null) {
			return;
		}
		widget.visible = visible;
		widget.active = visible;
	}

	// ------------------------------------------------------------------
	// State synchronisation
	// ------------------------------------------------------------------

	/** Pushes the whole working style into every widget (used on open/mode change). */
	public void syncFromStyle() {
		this.updating = true;
		try {
			this.alphaSlider.setValue(this.style.getOpacity());
			syncDerivedFromColor(true);
		} finally {
			this.updating = false;
		}
		refreshLabels();
		applyVisibility();
	}

	/**
	 * Mirrors the active stop's colour into the hue ramp and the hex field.
	 *
	 * @param moveRamp whether to reposition the hue ramp; {@code false} when the
	 *                 ramp itself is the source of the change (repositioning it
	 *                 mid-drag would fight the user's pointer)
	 */
	private void syncDerivedFromColor(boolean moveRamp) {
		boolean was = this.updating;
		this.updating = true;
		try {
			String hex = this.style.getColor(this.activeStop);
			if (moveRamp) {
				int rgb = ColorUtils.parseHex(hex) & 0xFFFFFF;
				this.hueSlider.setValue(ColorUtils.rgbToHsv(rgb).h / 360.0f);
			}
			if (!this.hexBox.isFocused()) {
				this.hexBox.setValue(hex);
			}
		} finally {
			this.updating = was;
		}
	}

	/**
	 * Composes a new colour for {@code stop} from a hue fraction while preserving
	 * the stop's existing saturation and brightness.
	 */
	private int applyHue(int stop, double hueFraction) {
		int existing = ColorUtils.parseHex(this.style.getColor(stop)) & 0xFFFFFF;
		ColorUtils.Hsv hsv = ColorUtils.rgbToHsv(existing);
		float saturation = hsv.s < 0.0001f ? 1.0f : hsv.s;
		float value = hsv.v < 0.0001f ? 1.0f : hsv.v;
		float hueDegrees = (float) ((((hueFraction % 1.0) + 1.0) % 1.0) * 360.0);
		return ColorUtils.hsvToRgb(new ColorUtils.Hsv(hueDegrees, saturation, value)) & 0xFFFFFF;
	}

	/**
	 * Refreshes every toggle's caption. Active options are drawn bold via an
	 * explicit {@link ChatFormatting#BOLD} style, which always renders (a legacy
	 * {@code \u00a7l} marker inside {@code Component.literal} is not guaranteed to
	 * be honoured by widget rendering).
	 */
	private void refreshLabels() {
		this.modeButton.setMessage(Component.literal(I18n.tr(this.style.isGradient() ? Keys.MODE_GRADIENT : Keys.MODE_SOLID)));
		this.boldButton.setMessage(emphasise(I18n.tr(Keys.BOLD_LABEL), this.style.isBold()));
		this.italicButton.setMessage(emphasise(I18n.tr(Keys.ITALIC_LABEL), this.style.isItalic()));
		this.miniCapsButton.setMessage(emphasise(
				TextStylingEngine.toMiniCaps("Aa"), this.style.isMiniCaps()));
		this.enabledButton.setMessage(Component.literal(I18n.tr(this.style.isEnabled() ? Keys.ENABLED_ON : Keys.ENABLED_OFF)));

		this.alignLeftButton.setMessage(emphasise(I18n.tr(Keys.ALIGN_LEFT_LABEL), this.style.isAlignLeft()));
		this.alignCenterButton.setMessage(emphasise(I18n.tr(Keys.ALIGN_CENTER_LABEL), this.style.isAlignCenter()));
		this.alignRightButton.setMessage(emphasise(I18n.tr(Keys.ALIGN_RIGHT_LABEL), this.style.isAlignRight()));
	}

	private static Component emphasise(String glyph, boolean selected) {
		Component base = Component.literal(glyph);
		return selected ? base.copy().withStyle(ChatFormatting.BOLD) : base;
	}

	private void toggleMode() {
		this.style.setMode(this.style.isGradient() ? ServerStyle.MODE_SOLID : ServerStyle.MODE_GRADIENT);
		this.activeStop = 0;
		commit();
		syncFromStyle();
	}

	private void onHexTyped(String value) {
		if (this.updating) {
			return;
		}
		String raw = value == null ? "" : value.trim();
		if (raw.startsWith("#")) {
			raw = raw.substring(1);
		}
		if (raw.length() != 3 && raw.length() != 6) {
			return;
		}
		for (int i = 0; i < raw.length(); i++) {
			if (Character.digit(raw.charAt(i), 16) < 0) {
				return;
			}
		}
		String hex = ColorUtils.sanitiseHex(raw);
		boolean was = this.updating;
		this.updating = true;
		try {
			this.style.setColor(this.activeStop, hex);
			int rgb = ColorUtils.parseHex(hex) & 0xFFFFFF;
			this.hueSlider.setValue(ColorUtils.rgbToHsv(rgb).h / 360.0f);
		} finally {
			this.updating = was;
		}
		commit();
		rememberColor(hex);
	}

	/** Number of stops currently in play (1 when solid). */
	private int activeStopCount() {
		return this.style.isGradient() ? this.style.gradientStopCount() : 1;
	}

	// ------------------------------------------------------------------
	// Rendering (26.1+ extract API)
	// ------------------------------------------------------------------

	/**
	 * Extracts the backdrop, section labels and the live preview. The panel is
	 * registered on the screen before its children, so this runs first and the
	 * widgets extract on top.
	 */
	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Self-heal: re-derive the anchor immediately before drawing so the panel
		// can never be stale (or stuck at the 0,0 origin) after a resize.
		relayout();
		int left = getX();
		int top = getY();
		int right = left + WIDTH;
		int bottom = top + this.height;

		drawBackdrop(graphics, left, top, right, bottom);
		drawCards(graphics, left, top);
		drawHeader(graphics, left, top, right);
		drawPreview(graphics, left, top);
		drawColourSection(graphics, left, top);
		drawRecentColors(graphics);
		drawSectionLabels(graphics, left, top);
	}

	/**
	 * Draws the three section cards. Each card is a slightly darker well with a
	 * light top/left edge and a dark bottom/right edge, so the COLOUR, STYLE and
	 * LAYOUT groups read as separate blocks instead of one dense column.
	 */
	private void drawCards(GuiGraphicsExtractor graphics, int left, int top) {
		int x = left + PAD - 3;
		int w = INNER + 6;

		// Never let the card edge creep up into the history row above it: in the
		// tiny tier the two rows are only one pixel apart.
		int colourTop = Math.max(top + this.colourLabelY - 2, top + this.historyY + this.rowH);
		int colourBottom = top + this.recentY + this.recentH + 1;
		drawCard(graphics, x, colourTop, w, colourBottom - colourTop);

		int styleTop = top + this.styleLabelY - 2;
		int styleBottom = top + this.formatY + this.rowH + 1;
		drawCard(graphics, x, styleTop, w, styleBottom - styleTop);

		int layoutTop = top + this.layoutLabelY - 2;
		int layoutBottom = top + this.alignY + this.rowH + 1;
		drawCard(graphics, x, layoutTop, w, layoutBottom - layoutTop);
	}

	private void drawCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x, y, x + w, y + h, 0x33000000);
		graphics.fill(x, y, x + w, y + 1, 0x22FFFFFF);
		graphics.fill(x, y + h - 1, x + w, y + h, 0x22000000);
		graphics.fill(x, y, x + 1, y + h, 0x22FFFFFF);
		graphics.fill(x + w - 1, y, x + w, y + h, 0x22000000);
	}

	/**
	 * Panel chrome: a near-opaque black body (so text behind it never shows
	 * through), a light one-pixel border, and a brighter header band. The same
	 * visual grammar as the game's own dialogs.
	 */
	private void drawBackdrop(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, 0xF0101010);
		graphics.fill(left, top, right, top + this.headerH, 0xFF2A2A2A);
		graphics.fill(left, top + this.headerH, right, top + this.headerH + 1, 0xFF000000);

		// Outer border: a light inner line so the panel edge catches the eye on any
		// background, then a dark line beneath for depth.
		graphics.outline(left, top, WIDTH, this.height, 0xFF000000);
		graphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFF5A5A5A);
	}

	private void drawHeader(GuiGraphicsExtractor graphics, int left, int top, int right) {
		int textY = top + (this.headerH - 8) / 2;
		graphics.text(this.font, getMessage(), left + PAD, textY, 0xFFFFFFFF, true);
		// Accent swatch reflecting the current gradient, between title and switch.
		int swatchW = 34;
		int swatchH = Math.min(8, this.headerH - 4);
		int swatchX = right - PAD - 34 - 6 - swatchW;
		int swatchY = top + (this.headerH - swatchH) / 2;
		drawGradientStrip(graphics, swatchX, swatchY, swatchX + swatchW, swatchY + swatchH);
	}

	/**
	 * The live WYSIWYG preview: the actual styled name, exactly as the multiplayer
	 * list will render it, inside the same dark well the list uses.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int left, int top) {
		if (this.previewH <= 0) {
			// The tiny tier trades the preview strip for the history and
			// recent-colour rows.
			return;
		}
		int x = left + PAD;
		int y = top + this.previewY;
		int w = INNER;

		graphics.fill(x, y, x + w, y + this.previewH, TextStylingEngine.LIST_BACKGROUND);
		graphics.fill(x, y, x + w, y + 1, 0x40FFFFFF);

		Component component = TextStylingEngine.buildComponent(previewText(), this.style);
		int textWidth = this.font.width(component);
		int textY = y + (this.previewH - 8) / 2;
		if (textWidth <= w - 6) {
			int textX = x + TextStylingEngine.alignedPadding(w, textWidth, this.style.getAlignment());
			graphics.text(this.font, component, textX, textY, 0xFFFFFFFF, false);
		} else {
			// Too wide for the strip: clip rather than letting it bleed into the frame.
			graphics.enableScissor(x + 1, y + 1, x + w - 1, y + this.previewH - 1);
			graphics.text(this.font, component, x + 3, textY, 0xFFFFFFFF, false);
			graphics.disableScissor();
		}
	}

	/**
	 * Text shown in the preview strip. A sample word rather than the real server
	 * name: the name already has its own live WYSIWYG field above, and a fixed
	 * sample keeps the strip readable regardless of what the user typed.
	 */
	private String previewText() {
		String sample = I18n.tr(this.style.isGradient() ? Keys.PREVIEW_GRADIENT : Keys.PREVIEW_SOLID);
		return TextStylingEngine.applyMiniCaps(sample, this.style);
	}

	/**
	 * Draws the draggable gradient bar: the blended strip plus one handle per stop.
	 *
	 * <p>The strip is the gradient itself, sampled with the stops' real positions,
	 * so what the user sees while dragging is literally what renders on the server
	 * list. Handles are drawn as small colour swatches riding on top of the bar.</p>
	 */
	private void drawColourSection(GuiGraphicsExtractor graphics, int left, int top) {
		int barLeft = left + PAD;
		int barRight = left + WIDTH - PAD;
		int barTop = top + this.barY;
		int barBottom = barTop + this.barH;

		drawGradientBar(graphics, barLeft, barTop, barRight, barBottom);

		int stops = activeStopCount();
		for (int i = 0; i < stops; i++) {
			drawStopHandle(graphics, barLeft, barTop, barRight, i);
		}
	}

	/** The gradient strip itself, sampled per pixel with the live stop positions. */
	private void drawGradientBar(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		List<String> colors = this.style.getColors();
		if (colors == null || colors.isEmpty()) {
			graphics.fill(left, top, right, bottom, 0xFFFFFFFF);
			return;
		}
		boolean gradient = this.style.isGradient() && colors.size() > 1;
		List<Float> positions = this.style.getPositions();
		int span = Math.max(1, right - left);
		for (int x = left; x < right; x++) {
			float t = (float) (x - left) / span;
			int argb = gradient
					? ColorUtils.gradientAt(colors, positions, t, 1.0f)
					: (ColorUtils.parseHex(colors.get(0)) | 0xFF000000);
			graphics.fill(x, top, x + 1, bottom, argb);
		}
		// Frame the bar so it reads as a groove, not a floating rectangle.
		graphics.fill(left, top, right, top + 1, 0xFF000000);
		graphics.fill(left, bottom - 1, right, bottom, 0xFF000000);
	}

	/**
	 * Draws one stop handle on the bar: a small square of the stop's colour with a
	 * white frame when it is the selected stop. Handles for movable (interior)
	 * stops get a pointed top to signal "this can be dragged".
	 */
	private void drawStopHandle(GuiGraphicsExtractor graphics, int barLeft, int barTop, int barRight,
								int index) {
		int x = stopHandleX(barLeft, barRight, index);
		int rgb = ColorUtils.parseHex(this.style.getColor(index)) & 0xFFFFFF;
		boolean selected = index == this.activeStop;

		int top = barTop - this.handleHalf;
		int bottom = barTop + this.barH + this.handleHalf;
		int left = x - HANDLE_W_HALF;
		int right = x + HANDLE_W_HALF;

		// Dark backing so the handle always reads against any colour behind it.
		graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF000000);
		graphics.fill(left, top, right, bottom, 0xFF000000 | rgb);

		int frame = selected ? 0xFFFFFFFF : 0xFF9A9A9A;
		graphics.fill(left, top, right, top + 1, frame);
		graphics.fill(left, bottom - 1, right, bottom, frame);
		graphics.fill(left, top, left + 1, bottom, frame);
		graphics.fill(right - 1, top, right, bottom, frame);

		// A movable handle gets a bright centre line as an affordance.
		if (this.style.isPositionMovable(index)) {
			graphics.fill(left + 1, top + 2, right - 1, top + 3, 0x60FFFFFF);
		}
	}

	/** Screen X of the centre of a stop's handle on the gradient bar. */
	private int stopHandleX(int barLeft, int barRight, int index) {
		float position = this.style.getPosition(index);
		return barLeft + Math.round(position * (barRight - barLeft));
	}

	/** Inverse of {@link #stopHandleX}: a screen X mapped back to a {@code 0..1} stop position. */
	private float stopPositionFromX(double mouseX, int barLeft, int barRight) {
		int span = Math.max(1, barRight - barLeft);
		return (float) Math.max(0.0, Math.min(1.0, (mouseX - barLeft) / span));
	}

	private void drawSectionLabels(GuiGraphicsExtractor graphics, int left, int top) {
		int labelColor = 0xFFB8B8B8;
		sectionLabel(graphics, left + PAD, top + this.colourLabelY, I18n.tr(Keys.SECTION_COLOUR), labelColor);
		sectionLabel(graphics, left + PAD, top + this.styleLabelY, I18n.tr(Keys.SECTION_STYLE), labelColor);
		sectionLabel(graphics, left + PAD, top + this.layoutLabelY, I18n.tr(Keys.SECTION_LAYOUT), labelColor);
	}

	// ------------------------------------------------------------------
	// Recent colours
	// ------------------------------------------------------------------

	/** Left edge of the swatch strip, in screen coordinates. */
	private int recentStripX() {
		return getX() + PAD + (INNER - RECENT_STRIP_W) / 2;
	}

	/** Top edge of the swatch strip, in screen coordinates. */
	private int recentStripY() {
		return getY() + this.recentY;
	}

	/**
	 * Draws the remembered colours as a full-width row of swatches under the hex
	 * field.
	 *
	 * <p>They used to share the hex row, which squeezed the hex field down to
	 * 105 px and left eight 6 px slivers that were hard to hit. A dedicated row
	 * gives each swatch 19 px and keeps the hex field at its full width.</p>
	 */
	private void drawRecentColors(GuiGraphicsExtractor graphics) {
		int x = recentStripX();
		int y = recentStripY();
		int h = this.recentH;
		for (int i = 0; i < MAX_RECENT; i++) {
			int sx = x + i * (RECENT_SWATCH_W + RECENT_SWATCH_GAP);
			if (i < this.recentColors.size()) {
				int rgb = ColorUtils.parseHex(this.recentColors.get(i)) & 0xFFFFFF;
				graphics.fill(sx, y, sx + RECENT_SWATCH_W, y + h, 0xFF000000 | rgb);
				graphics.fill(sx, y, sx + RECENT_SWATCH_W, y + 1, 0x60FFFFFF);
			} else {
				// Empty slot: a faint well so the strip keeps its shape.
				graphics.fill(sx, y, sx + RECENT_SWATCH_W, y + h, 0x30FFFFFF);
			}
		}
	}

	/**
	 * Maps a click to a recent-colour slot.
	 *
	 * @return the slot index, or {@code -1} when the click missed the strip or the
	 *         slot is empty
	 */
	private int recentSwatchAt(double mouseX, double mouseY) {
		if (this.recentColors.isEmpty()) {
			return -1;
		}
		int x = recentStripX();
		int y = recentStripY();
		if (mouseY < y || mouseY >= y + this.recentH) {
			return -1;
		}
		int offset = (int) (mouseX - x);
		if (offset < 0) {
			return -1;
		}
		int pitch = RECENT_SWATCH_W + RECENT_SWATCH_GAP;
		int slot = offset / pitch;
		if (slot < 0 || slot >= MAX_RECENT || slot >= this.recentColors.size()) {
			return -1;
		}
		// Reject the 1 px gap between two swatches.
		if (offset % pitch >= RECENT_SWATCH_W) {
			return -1;
		}
		return slot;
	}

	private void sectionLabel(GuiGraphicsExtractor graphics, int x, int y, String label, int color) {
		graphics.text(this.font, Component.literal(label), x, y, color, false);
	}

	/** Horizontal gradient strip from the style's stops, used in the header. */
	private void drawGradientStrip(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		List<String> colors = this.style.getColors();
		if (colors == null || colors.isEmpty()) {
			graphics.fill(left, top, right, bottom, 0xFFFFFFFF);
			return;
		}
		boolean gradient = this.style.isGradient() && colors.size() > 1;
		List<Float> positions = this.style.getPositions();
		int span = Math.max(1, right - left);
		for (int x = left; x < right; x++) {
			float t = (float) (x - left) / span;
			int argb = gradient
					? ColorUtils.gradientAt(colors, positions, t, 1.0f)
					: (ColorUtils.parseHex(colors.get(0)) | 0xFF000000);
			graphics.fill(x, top, x + 1, bottom, argb);
		}
		graphics.fill(left, top, right, top + 1, 0xFF000000);
		graphics.fill(left, bottom - 1, right, bottom, 0xFF000000);
	}

	// ------------------------------------------------------------------
	// Gradient bar interaction (the bar is painted, not a widget: hit-test here)
	// ------------------------------------------------------------------

	/**
	 * Restricts this widget's hit area to the gradient bar's handles only.
	 *
	 * <p>{@code ContainerEventHandler.getChildAt} walks the screen's child list and
	 * returns the <b>first</b> entry whose {@code isMouseOver} is true &mdash; it does
	 * not fall through to later siblings. Because this panel is registered before
	 * its own children (so the backdrop renders underneath them), a full-rectangle
	 * hit area would swallow every click in the panel and leave the sliders, the hex
	 * field and all buttons permanently dead.</p>
	 *
	 * <p>Returning true only over a handle makes {@code getChildAt} skip this widget
	 * everywhere else, letting the real controls receive their own events, while
	 * still routing handle clicks and drags here.</p>
	 */
	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return this.visible && this.requestedVisible
				&& (stopHandleAt(mouseX, mouseY) >= 0 || recentSwatchAt(mouseX, mouseY) >= 0);
	}

	/** Bar bounds in screen space, as {@code {left, right}}. */
	private int[] barBounds() {
		int left = getX() + PAD;
		int right = getX() + WIDTH - PAD;
		return new int[]{left, right};
	}

	/**
	 * Index of the stop handle under the given screen point, or {@code -1}.
	 *
	 * <p>The grab band is taller than the bar itself so a handle is comfortable to
	 * hit, and overlapping handles resolve to the nearest one rather than to
	 * whichever happens to come first in the list.</p>
	 */
	private int stopHandleAt(double mouseX, double mouseY) {
		int stops = activeStopCount();
		if (stops <= 0) {
			return -1;
		}
		int top = getY() + this.barY - this.handleHalf - 2;
		int bottom = getY() + this.barY + this.barH + this.handleHalf + 2;
		if (mouseY < top || mouseY >= bottom) {
			return -1;
		}

		int[] bounds = barBounds();
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < stops; i++) {
			int cx = stopHandleX(bounds[0], bounds[1], i);
			double distance = Math.abs(mouseX - cx);
			if (distance <= HANDLE_W_HALF + 2 && distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Never compare the raw button number: 26.3 switched to SDL's 1-based
		// numbering (left = 1) while 26.1/26.2 still use GLFW's 0-based one.
		// isValidClickButton() is the version-correct vanilla helper.
		if (!this.visible || !this.requestedVisible || !isValidClickButton(event.buttonInfo())) {
			return false;
		}
		int swatch = recentSwatchAt(event.x(), event.y());
		if (swatch >= 0) {
			applyRecentColor(this.recentColors.get(swatch));
			return true;
		}
		int index = stopHandleAt(event.x(), event.y());
		if (index < 0) {
			return false;
		}
		this.activeStop = index;
		// A movable handle starts a drag immediately; the end handles are pinned, so
		// clicking them only selects (which re-points the hue ramp at that stop).
		if (this.style.isPositionMovable(index)) {
			this.draggingStop = index;
		}
		syncDerivedFromColor(true);
		return true;
	}

	/**
	 * Drags the handle selected in {@link #mouseClicked}.
	 *
	 * <p>Not gated on {@code isMouseOver}: the pointer leaves the bar horizontally
	 * the moment it moves, and the drag must continue until the button is released.
	 * The screen only routes drags here while this widget holds focus.</p>
	 */
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!this.visible || !this.requestedVisible || this.draggingStop < 0) {
			return false;
		}
		if (!isValidClickButton(event.buttonInfo())) {
			return false;
		}
		int[] bounds = barBounds();
		float position = stopPositionFromX(event.x(), bounds[0], bounds[1]);
		int index = this.draggingStop;
		this.style.setPosition(index, position);
		// The style may have clamped the position against its neighbours; mirror the
		// clamped value back so a dragged handle never visually separates from its
		// stored position.
		//
		// No commit() here: a drag fires dozens of times per second and would fill
		// the whole undo history with intermediate positions. The single history
		// entry for the gesture is pushed in mouseReleased().
		this.modified = true;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		// One undo entry per drag gesture: the snapshot pushed here is the style as
		// it was before the handle started moving.
		if (this.draggingStop >= 0) {
			commit();
		}
		this.draggingStop = -1;
		return false;
	}

	/**
	 * Called when the panel hides or relayouts, so a half-finished drag can never
	 * outlive the bar it belonged to.
	 */
	private void clearDrag() {
		this.draggingStop = -1;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
		defaultButtonNarrationText(narrationElementOutput);
	}
}