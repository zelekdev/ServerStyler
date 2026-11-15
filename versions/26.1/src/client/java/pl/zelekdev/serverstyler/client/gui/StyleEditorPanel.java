package pl.zelekdev.serverstyler.client.gui;

import pl.zelekdev.serverstyler.config.ColorUtils;
import pl.zelekdev.serverstyler.config.ServerStyle;
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
 * Minecraft rather than as an overlay bolted on top. Sections are separated by
 * labelled hairlines:</p>
 * <ol>
 *   <li><b>Preview</b> &mdash; the finished name, drawn exactly as the multiplayer
 *       list will draw it, inside the same dark well the list uses.</li>
 *   <li><b>Colour</b> &mdash; clickable stop chips, a hue ramp, an opacity ramp,
 *       and the {@code #RRGGBB} field.</li>
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

	/** Panel size in GUI-scaled pixels. */
	public static final int WIDTH = 180;
	public static final int HEIGHT = 254;

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

	// ---- Local layout constants (relative to the panel origin) ----------------

	/** Inner content margin, left and right. */
	private static final int PAD = 8;
	/** Content width: the space every full-width row spans. */
	private static final int INNER = WIDTH - PAD * 2;
	/** Height of a standard control row. */
	private static final int ROW_H = 16;
	/** Vertical pitch between stacked control rows. */
	private static final int PITCH = ROW_H + 4;

	/** Header band height, holding the title and the on/off switch. */
	private static final int HEADER_H = 18;
	/** Live preview well. */
	private static final int PREVIEW_Y = 22;
	private static final int PREVIEW_H = 20;

	/** "COLOUR" section: label row also carries the +/- buttons. */
	private static final int COLOUR_LABEL_Y = 48;

	/**
	 * Draggable gradient bar. Sits directly under the COLOUR label, exactly where a
	 * gradient editor puts it: the strip shows the resulting blend and each stop
	 * rides on it as a handle that can be dragged left or right.
	 */
	private static final int BAR_Y = 64;
	private static final int BAR_H = 14;
	/** Handle marker footprint, centred on the stop position. */
	private static final int HANDLE_HALF = 4;
	/** Extra vertical grab area above the bar, so a handle is easy to hit. */
	private static final int HANDLE_GRAB_ABOVE = 6;

	private static final int HUE_Y = BAR_Y + BAR_H + HANDLE_GRAB_ABOVE + 6;
	private static final int ALPHA_Y = HUE_Y + PITCH;
	private static final int HEX_Y = ALPHA_Y + PITCH;

	/** "STYLE" section. */
	private static final int STYLE_LABEL_Y = HEX_Y + ROW_H + 10;
	private static final int MODE_Y = STYLE_LABEL_Y + 16;
	private static final int FORMAT_Y = MODE_Y + PITCH;

	/** "LAYOUT" section. */
	private static final int LAYOUT_LABEL_Y = FORMAT_Y + ROW_H + 10;
	private static final int ALIGN_Y = LAYOUT_LABEL_Y + 16;

	/** Thirds of {@link #INNER}, leaving 3 px gaps between the three cells. */
	private static final int THIRD_W = (INNER - 6) / 3;
	/** Halves of {@link #INNER}, leaving a 4 px gap. */
	private static final int HALF_W = (INNER - 4) / 2;

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

	public StyleEditorPanel(ServerStyle style, Font font, Anchor anchor) {
		super(0, 0, WIDTH, HEIGHT, Component.literal("ServerStyler"));
		this.style = style;
		this.font = font;
		this.anchor = anchor;
		// Backdrop + labels only - the panel must never capture input itself.
		this.active = false;
		this.visible = false;
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
	 */
	public void relayout() {
		int screenWidth = this.anchor.screenWidth();
		int screenHeight = this.anchor.screenHeight();
		int fieldRight = this.anchor.fieldRight();
		this.dimsValid = screenWidth > WIDTH && screenHeight > HEIGHT && fieldRight > 0;
		if (this.dimsValid) {
			layout(panelX(this.anchor.fieldLeft(), fieldRight, screenWidth),
					panelY(this.anchor.fieldTop(), screenHeight));
		} else {
			applyVisibility();
		}
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
	public static int panelY(int fieldTop, int screenHeight) {
		return clamp(fieldTop, MARGIN, screenHeight - HEIGHT - MARGIN);
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
			this.modified = true;
			this.style.setColor(this.activeStop, ColorUtils.toHex(applyHue(this.activeStop, hue)));
			syncDerivedFromColor(false);
		});
		this.widgetSink.accept(this.hueSlider);

		this.alphaSlider = new HorizontalAlphaSlider(this.font, 0, 0, INNER, ROW_H, 1.0f,
				() -> this.style.getPrimaryRgb() & 0xFFFFFF, alpha -> {
			if (this.updating) {
				return;
			}
			this.modified = true;
			this.style.setOpacity((float) alpha);
		});
		this.widgetSink.accept(this.alphaSlider);

		this.hexBox = new EditBox(this.font, 0, 0, INNER, ROW_H, Component.literal("Hex"));
		this.hexBox.setMaxLength(7);
		this.hexBox.setResponder(this::onHexTyped);
		this.hexBox.setTooltip(Tooltip.create(Component.literal("Colour of the selected stop")));
		this.widgetSink.accept(this.hexBox);
	}

	private void createStyleControls() {
		this.modeButton = Button.builder(Component.literal("Mode"), b -> {
			this.modified = true;
			toggleMode();
		}).bounds(0, 0, INNER, ROW_H).build();
		this.modeButton.setTooltip(Tooltip.create(
				Component.literal("Solid colour or multi-stop gradient")));
		this.widgetSink.accept(this.modeButton);

		this.boldButton = Button.builder(Component.literal("B"), b -> {
			this.modified = true;
			this.style.setBold(!this.style.isBold());
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.boldButton.setTooltip(Tooltip.create(Component.literal("Bold")));
		this.widgetSink.accept(this.boldButton);

		this.italicButton = Button.builder(Component.literal("I"), b -> {
			this.modified = true;
			this.style.setItalic(!this.style.isItalic());
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.italicButton.setTooltip(Tooltip.create(Component.literal("Italic")));
		this.widgetSink.accept(this.italicButton);

		this.miniCapsButton = Button.builder(
				Component.literal(TextStylingEngine.toMiniCaps("Aa")), b -> {
					this.modified = true;
					this.style.setMiniCaps(!this.style.isMiniCaps());
					refreshLabels();
				}).bounds(0, 0, INNER - THIRD_W * 2 - 6, ROW_H).build();
		this.miniCapsButton.setTooltip(Tooltip.create(Component.literal(
				"Mini Caps - render the name in small capitals.\n"
						+ "Polish diacritics are folded to their base letter")));
		this.widgetSink.accept(this.miniCapsButton);

		// On/off switch lives in the header: it is the most important toggle and
		// deserves permanent prominence rather than a slot in a list.
		this.enabledButton = Button.builder(Component.literal("On"), b -> {
			this.modified = true;
			this.style.setEnabled(!this.style.isEnabled());
			refreshLabels();
		}).bounds(0, 0, 34, 12).build();
		this.enabledButton.setTooltip(Tooltip.create(Component.literal(
				"Apply this style to the server name.\n"
						+ "Turning it off keeps the style saved but unused.")));
		this.widgetSink.accept(this.enabledButton);

		this.addStopButton = Button.builder(Component.literal("+"), b -> {
			this.modified = true;
			// Remember the spacing before the insert so the newly added stop can be
			// identified afterwards: the new one is the index whose position is not
			// present in the old list. New stops are inserted into the widest gap, not
			// appended, so counting alone would select the wrong stop.
			List<Float> before = this.style.getPositions();
			this.style.setMode(ServerStyle.MODE_GRADIENT);
			this.style.addGradientStop();
			this.activeStop = findInsertedStop(before);
			syncFromStyle();
		}).bounds(0, 0, 13, 11).build();
		this.addStopButton.setTooltip(Tooltip.create(Component.literal("Add gradient stop")));
		this.widgetSink.accept(this.addStopButton);

		this.removeStopButton = Button.builder(Component.literal("-"), b -> {
			this.modified = true;
			// Remove the currently selected stop when it is removable, otherwise the
			// last interior one - so the button always does something predictable.
			int target = this.activeStop;
			if (!this.style.isPositionMovable(target)) {
				target = this.style.gradientStopCount() - 2;
			}
			this.style.removeGradientStopAt(target);
			this.activeStop = Math.max(0, Math.min(this.activeStop, this.style.gradientStopCount() - 1));
			syncFromStyle();
		}).bounds(0, 0, 13, 11).build();
		this.removeStopButton.setTooltip(Tooltip.create(Component.literal("Remove selected gradient stop")));
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
		this.alignLeftButton = Button.builder(Component.literal("L"), b -> {
			this.modified = true;
			this.style.setAlignment(ServerStyle.ALIGN_LEFT);
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignLeftButton.setTooltip(Tooltip.create(Component.literal("Align left")));
		this.widgetSink.accept(this.alignLeftButton);

		this.alignCenterButton = Button.builder(Component.literal("C"), b -> {
			this.modified = true;
			this.style.setAlignment(ServerStyle.ALIGN_CENTER);
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignCenterButton.setTooltip(Tooltip.create(Component.literal("Align center")));
		this.widgetSink.accept(this.alignCenterButton);

		this.alignRightButton = Button.builder(Component.literal("R"), b -> {
			this.modified = true;
			this.style.setAlignment(ServerStyle.ALIGN_RIGHT);
			refreshLabels();
		}).bounds(0, 0, THIRD_W, ROW_H).build();
		this.alignRightButton.setTooltip(Tooltip.create(Component.literal("Align right")));
		this.widgetSink.accept(this.alignRightButton);
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
		place(this, originX, originY, WIDTH, HEIGHT);

		int col = originX + PAD;

		place(this.hueSlider, col, originY + HUE_Y, INNER, ROW_H);
		place(this.alphaSlider, col, originY + ALPHA_Y, INNER, ROW_H);
		place(this.hexBox, col, originY + HEX_Y, INNER, ROW_H);

		place(this.modeButton, col, originY + MODE_Y, INNER, ROW_H);
		place(this.boldButton, col, originY + FORMAT_Y, THIRD_W, ROW_H);
		place(this.italicButton, col + THIRD_W + 3, originY + FORMAT_Y, THIRD_W, ROW_H);
		place(this.miniCapsButton, col + (THIRD_W + 3) * 2, originY + FORMAT_Y,
				INNER - THIRD_W * 2 - 6, ROW_H);

		place(this.alignLeftButton, col, originY + ALIGN_Y, THIRD_W, ROW_H);
		place(this.alignCenterButton, col + THIRD_W + 3, originY + ALIGN_Y, THIRD_W, ROW_H);
		place(this.alignRightButton, col + (THIRD_W + 3) * 2, originY + ALIGN_Y,
				INNER - THIRD_W * 2 - 6, ROW_H);

		// Header switch, top-right.
		place(this.enabledButton, originX + WIDTH - PAD - 34, originY + 3, 34, 12);

		// Stop add/remove, right-aligned in the COLOUR label row.
		place(this.addStopButton, originX + WIDTH - PAD - 28, originY + COLOUR_LABEL_Y - 1, 13, 11);
		place(this.removeStopButton, originX + WIDTH - PAD - 13, originY + COLOUR_LABEL_Y - 1, 13, 11);

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
		this.modeButton.setMessage(Component.literal(this.style.isGradient() ? "Gradient" : "Solid"));
		this.boldButton.setMessage(emphasise("B", this.style.isBold()));
		this.italicButton.setMessage(emphasise("I", this.style.isItalic()));
		this.miniCapsButton.setMessage(emphasise(
				TextStylingEngine.toMiniCaps("Aa"), this.style.isMiniCaps()));
		this.enabledButton.setMessage(Component.literal(this.style.isEnabled() ? "On" : "Off"));

		this.alignLeftButton.setMessage(emphasise("L", this.style.isAlignLeft()));
		this.alignCenterButton.setMessage(emphasise("C", this.style.isAlignCenter()));
		this.alignRightButton.setMessage(emphasise("R", this.style.isAlignRight()));
	}

	private static Component emphasise(String glyph, boolean selected) {
		Component base = Component.literal(glyph);
		return selected ? base.copy().withStyle(ChatFormatting.BOLD) : base;
	}

	private void toggleMode() {
		this.style.setMode(this.style.isGradient() ? ServerStyle.MODE_SOLID : ServerStyle.MODE_GRADIENT);
		this.activeStop = 0;
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
		this.modified = true;
		boolean was = this.updating;
		this.updating = true;
		try {
			this.style.setColor(this.activeStop, hex);
			int rgb = ColorUtils.parseHex(hex) & 0xFFFFFF;
			this.hueSlider.setValue(ColorUtils.rgbToHsv(rgb).h / 360.0f);
		} finally {
			this.updating = was;
		}
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
		int bottom = top + HEIGHT;

		drawBackdrop(graphics, left, top, right, bottom);
		drawHeader(graphics, left, top, right);
		drawPreview(graphics, left, top);
		drawColourSection(graphics, left, top);
		drawSectionLabels(graphics, left, top);
	}

	/**
	 * Panel chrome: a near-opaque black body (so text behind it never shows
	 * through), a light one-pixel border, and a brighter header band. The same
	 * visual grammar as the game's own dialogs.
	 */
	private void drawBackdrop(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, 0xF0101010);
		graphics.fill(left, top, right, top + HEADER_H, 0xFF2A2A2A);
		graphics.fill(left, top + HEADER_H, right, top + HEADER_H + 1, 0xFF000000);

		// Outer border: a light inner line so the panel edge catches the eye on any
		// background, then a dark line beneath for depth.
		graphics.outline(left, top, WIDTH, HEIGHT, 0xFF000000);
		graphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFF5A5A5A);
	}

	private void drawHeader(GuiGraphicsExtractor graphics, int left, int top, int right) {
		graphics.text(this.font, getMessage(), left + PAD, top + 5, 0xFFFFFFFF, true);
		// Accent swatch reflecting the current gradient, between title and switch.
		int swatchW = 34;
		int swatchX = right - PAD - 34 - 6 - swatchW;
		drawGradientStrip(graphics, swatchX, top + 5, swatchX + swatchW, top + 13);
	}

	/**
	 * The live WYSIWYG preview: the actual styled name, exactly as the multiplayer
	 * list will render it, inside the same dark well the list uses.
	 */
	private void drawPreview(GuiGraphicsExtractor graphics, int left, int top) {
		int x = left + PAD;
		int y = top + PREVIEW_Y;
		int w = INNER;

		graphics.fill(x, y, x + w, y + PREVIEW_H, TextStylingEngine.LIST_BACKGROUND);
		graphics.fill(x, y, x + w, y + 1, 0x40FFFFFF);

		Component component = TextStylingEngine.buildComponent(previewText(), this.style);
		int textWidth = this.font.width(component);
		int textY = y + (PREVIEW_H - 8) / 2;
		if (textWidth <= w - 6) {
			int textX = x + TextStylingEngine.alignedPadding(w, textWidth, this.style.getAlignment());
			graphics.text(this.font, component, textX, textY, 0xFFFFFFFF, false);
		} else {
			// Too wide for the strip: clip rather than letting it bleed into the frame.
			graphics.enableScissor(x + 1, y + 1, x + w - 1, y + PREVIEW_H - 1);
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
		String sample = this.style.isGradient() ? "Gradient preview" : "Solid preview";
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
		int barTop = top + BAR_Y;
		int barBottom = barTop + BAR_H;

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

		int top = barTop - HANDLE_HALF;
		int bottom = barTop + BAR_H + HANDLE_HALF;
		int left = x - HANDLE_HALF;
		int right = x + HANDLE_HALF;

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
		int labelColor = 0xFF9A9A9A;
		sectionHeader(graphics, left + PAD, top + COLOUR_LABEL_Y + 3, "COLOUR", labelColor);
		sectionHeader(graphics, left + PAD, top + STYLE_LABEL_Y, "STYLE", labelColor);
		sectionHeader(graphics, left + PAD, top + LAYOUT_LABEL_Y, "LAYOUT", labelColor);
	}

	private void sectionHeader(GuiGraphicsExtractor graphics, int x, int y, String label, int color) {
		graphics.text(this.font, Component.literal(label), x, y, color, false);
		int labelWidth = this.font.width(label);
		int lineX = x + labelWidth + 4;
		int lineY = y + 4;
		graphics.fill(lineX, lineY, getX() + WIDTH - PAD, lineY + 1, 0x30FFFFFF);
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
		return this.visible && this.requestedVisible && stopHandleAt(mouseX, mouseY) >= 0;
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
		int top = getY() + BAR_Y - HANDLE_HALF - 2;
		int bottom = getY() + BAR_Y + BAR_H + HANDLE_HALF + 2;
		if (mouseY < top || mouseY >= bottom) {
			return -1;
		}

		int[] bounds = barBounds();
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < stops; i++) {
			int cx = stopHandleX(bounds[0], bounds[1], i);
			double distance = Math.abs(mouseX - cx);
			if (distance <= HANDLE_HALF + 2 && distance < bestDistance) {
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
		this.modified = true;
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
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