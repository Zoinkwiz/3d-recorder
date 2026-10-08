package com.extrinsiccognition.embertide.viewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultCaret;

/** The sidebar's Watch section. */
final class WatchPanel extends JPanel
{
	private final Viewer viewer;
	private final JTextArea file = text("Choose a saved recording to begin.");
	private final JTextArea status = text("Enter your POH to place a replay.");
	private final JTextArea details = text("");
	private final JButton load = new JButton("Watch a recording…");
	private final JButton play = new JButton("Play");
	private final JButton place = new JButton("Place here");
	private final JButton clear = new JButton("Clear");
	private final JSlider seek = new JSlider(0, 10000, 0);
	private final JLabel time = new JLabel("0:00 / 0:00");
	private final JComboBox<String> mode = new JComboBox<>(new String[]{"Miniature", "Life size"});
	private final JSpinner size = new JSpinner(new SpinnerNumberModel(4, 2, 12, 1));
	private final JSpinner radius = new JSpinner(new SpinnerNumberModel(12, 4, 24, 1));
	private final JCheckBox surroundingMap = new JCheckBox("Wider area", false);
	private final JCheckBox allFloors = new JCheckBox("All floors", true);
	private final JSpinner objectLimit = new JSpinner(new SpinnerNumberModel(ReplayRenderer.DEFAULT_OBJECT_LIMIT, 128, ReplayRenderer.MAX_OBJECT_LIMIT, 256));
	private final JSpinner extraArea = new JSpinner(new SpinnerNumberModel(12, 0, 24, 1));
	private final JSpinner lift = new JSpinner(new SpinnerNumberModel(96, 0, 512, 16));
	private final JCheckBox ground = new JCheckBox("Ground", true);
	private final JCheckBox scenery = new JCheckBox("Scenery", true);
	private final JCheckBox isolate = new JCheckBox("Hide surroundings", true);
	private boolean updating;
	private double duration;

	WatchPanel(Viewer viewer)
	{
		this.viewer = viewer;
		file.setRows(3); status.setRows(4); details.setRows(4);
		setOpaque(false);
		JPanel body = this; setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		JLabel heading = new JLabel("Watch in your house"); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
		stack(body, heading); stack(body, text("Play a saved recording back in your player-owned house, as a miniature or life size."));
		stack(body, load); stack(body, file); stack(body, mode);
		stack(body, row("Diorama width (tiles)", size));
		stack(body, row("Scene radius (tiles)", radius));
		surroundingMap.setToolTipText("Show more of the recorded area around the scene: the ground and objects the recording saved.");
		extraArea.setToolTipText("Extra tiles around the scene. Miniatures fit the expanded area into the chosen diorama width.");
		extraArea.setEnabled(false); stack(body, surroundingMap); stack(body, row("Extra area (tiles)", extraArea));
		allFloors.setToolTipText("Show available floors above and below the recorded player. Turn off to see inside buildings.");
		stack(body, allFloors);
		objectLimit.setToolTipText("Maximum recorded objects. Raise this if objects are omitted; lower it if playback is slow.");
		stack(body, row("Object limit", objectLimit));
		stack(body, row("Lift (128 = one tile)", lift));
		JPanel placement = new JPanel(new GridLayout(1, 2, 6, 0)); placement.add(place); placement.add(clear); stack(body, placement);
		JButton rotate = new JButton("Rotate 90°"); stack(body, rotate);
		stack(body, text("Or right-click a floor tile and choose Place Embertide replay."));
		stack(body, seek); stack(body, time);
		JPanel transport = new JPanel(new GridLayout(1, 2, 6, 0));
		transport.add(play); JButton restart = new JButton("Restart"); transport.add(restart); stack(body, transport);
		JComboBox<String> speed = new JComboBox<>(new String[]{"0.25×", "0.5×", "1×", "2×", "4×"}); speed.setSelectedIndex(2);
		JCheckBox loop = new JCheckBox("Loop", true);
		JPanel playback = new JPanel(new GridLayout(1, 2, 6, 0)); playback.add(speed); playback.add(loop); stack(body, playback);
		JPanel layers = new JPanel(new GridLayout(1, 2, 6, 0)); layers.add(ground); layers.add(scenery); stack(body, layers);
		isolate.setToolTipText("Hide house tiles, objects and other live actors while placed. Works with RuneLite's GPU plugin and 117 HD. Clear restores them.");
		stack(body, isolate);
		stack(body, status); stack(body, details);
		JTextArea note = text("Clear restores your surroundings. Hidden house walls still block movement. Visitors need their own viewer."); note.setRows(4);
		note.setForeground(new Color(170, 170, 170)); stack(body, note);

		load.addActionListener(e -> viewer.choose(this));
		play.addActionListener(e -> viewer.toggle()); place.addActionListener(e -> viewer.placeHere());
		clear.addActionListener(e -> viewer.clear()); rotate.addActionListener(e -> viewer.rotate());
		restart.addActionListener(e -> viewer.seek(0));
		seek.addChangeListener(e -> { if (!updating && !seek.getValueIsAdjusting()) { viewer.seek(duration * seek.getValue() / 10000.0); } });
		speed.addActionListener(e -> viewer.speed(new double[]{.25, .5, 1, 2, 4}[speed.getSelectedIndex()]));
		loop.addActionListener(e -> viewer.loop(loop.isSelected()));
		mode.addActionListener(e -> { updating = true; lift.setValue(mode.getSelectedIndex() == 0 ? 96 : 0); updating = false; settings(); });
		size.addChangeListener(e -> settings()); radius.addChangeListener(e -> settings()); lift.addChangeListener(e -> settings());
		ground.addActionListener(e -> settings()); scenery.addActionListener(e -> settings());
		surroundingMap.addActionListener(e -> settings()); extraArea.addChangeListener(e -> settings());
		allFloors.addActionListener(e -> settings());
		objectLimit.addChangeListener(e -> viewer.objectLimit((int) objectLimit.getValue()));
		isolate.addActionListener(e -> viewer.hideSurroundings(isolate.isSelected()));
		place.setEnabled(false); play.setEnabled(false); clear.setEnabled(false); seek.setEnabled(false);
	}

	private void settings()
	{
		if (updating) { return; }
		size.setEnabled(mode.getSelectedIndex() == 0);
		extraArea.setEnabled(surroundingMap.isSelected());
		viewer.settings(mode.getSelectedIndex() == 0, (int) size.getValue(), (int) radius.getValue(), (int) lift.getValue(),
			ground.isSelected(), scenery.isSelected(), surroundingMap.isSelected(), (int) extraArea.getValue(), allFloors.isSelected());
	}
	void update(String filename, String message, String detail, double elapsed, double total,
		boolean playing, boolean ready, boolean placed, boolean house, boolean loading)
	{
		int extension = filename.lastIndexOf('.');
		String shortName = filename.length() > 64 ? filename.substring(0, 42) + "..." + (extension > 42 ? filename.substring(extension) : "") : filename;
		setText(file, shortName); setText(status, message); setText(details, detail); duration = total;
		if (!filename.equals(file.getToolTipText())) { file.setToolTipText(filename); }
		if (!message.equals(status.getToolTipText())) { status.setToolTipText(message); }
		String playText = playing ? "Pause" : "Play";
		if (!playText.equals(play.getText())) { play.setText(playText); }
		play.setEnabled(ready && placed && !loading); place.setEnabled(ready && house && !loading);
		clear.setEnabled(placed); seek.setEnabled(ready && !loading);
		String loadText = loading ? "Open another…" : "Open recording…";
		if (!loadText.equals(load.getText())) { load.setText(loadText); }
		String timeText = format(elapsed) + " / " + format(total);
		if (!timeText.equals(time.getText())) { time.setText(timeText); }
		if (!seek.getValueIsAdjusting())
		{ updating = true; seek.setValue(total <= 0 ? 0 : (int) Math.round(10000 * elapsed / total)); updating = false; }
	}
	static String format(double seconds) { int s = (int) Math.max(0, seconds); return String.format("%d:%02d", s / 60, s % 60); }
	private static void setText(JTextArea area, String content)
	{ if (!content.equals(area.getText())) { area.setText(content); } }
	private static JTextArea text(String content)
	{
		JTextArea area = new JTextArea(content); area.setLineWrap(true); area.setWrapStyleWord(true); area.setEditable(false);
		// A read-only JTextArea still has a caret. Its default policy scrolls the
		// surrounding sidebar on setText(), even when the user scrolled elsewhere.
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		area.setFocusable(false);
		area.setOpaque(false); area.setFont(new JLabel().getFont()); area.setColumns(20); area.setRows(2); return area;
	}
	private static JPanel row(String label, JSpinner control)
	{ JPanel panel = new JPanel(new BorderLayout(6, 0)); panel.add(new JLabel(label), BorderLayout.CENTER); panel.add(control, BorderLayout.EAST); return panel; }
	private static void stack(JPanel parent, java.awt.Component component)
	{
		if (component instanceof javax.swing.JComponent)
		{
			javax.swing.JComponent c = (javax.swing.JComponent) component;
			c.setAlignmentX(LEFT_ALIGNMENT);
			Dimension preferred = c.getPreferredSize(); c.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(26, preferred.height)));
		}
		parent.add(component); parent.add(Box.createVerticalStrut(9));
	}
}
