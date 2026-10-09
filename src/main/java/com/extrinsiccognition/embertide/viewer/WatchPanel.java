package com.extrinsiccognition.embertide.viewer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;

/** The sidebar's Watch tab. Tuning lives in the plugin's settings. */
final class WatchPanel extends JPanel
{
	private final JTextArea file = text("Pick a recording to watch in your player-owned house.");
	private final JTextArea status = text("");
	private final JButton play = new JButton("Play");
	private final JButton place = new JButton("Place here");
	private final JButton clear = new JButton("Clear");
	private final JSlider seek = new JSlider(0, 10000, 0);
	private final JLabel time = new JLabel("0:00 / 0:00");
	private boolean updating;
	private double duration;

	WatchPanel(Viewer viewer)
	{
		setOpaque(false); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		JButton load = new JButton("Open recording…"), rotate = new JButton("Rotate 90°"), restart = new JButton("Restart");
		JComboBox<String> mode = new JComboBox<>(new String[]{"Miniature", "Life size"});
		JComboBox<String> speed = new JComboBox<>(new String[]{"0.25×", "0.5×", "1×", "2×", "4×"}); speed.setSelectedIndex(2);
		JCheckBox loop = new JCheckBox("Loop", true), isolate = new JCheckBox("Hide surroundings", true);
		isolate.setToolTipText("Hides the house, other players and pets while the replay is placed. House walls still block you. Clear puts them back.");
		status.setRows(4);
		JTextArea hint = text("More options in the plugin's settings."); hint.setForeground(new Color(170, 170, 170));
		for (JComponent c : new JComponent[]{load, file, mode, pair(place, clear), rotate, seek, time, pair(play, restart), pair(speed, loop), isolate, status, hint})
		{
			c.setAlignmentX(LEFT_ALIGNMENT); c.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(26, c.getPreferredSize().height)));
			add(c); add(Box.createVerticalStrut(8));
		}
		load.addActionListener(e -> viewer.choose(this));
		play.addActionListener(e -> viewer.toggle()); place.addActionListener(e -> viewer.placeHere());
		clear.addActionListener(e -> viewer.clear()); rotate.addActionListener(e -> viewer.rotate());
		restart.addActionListener(e -> viewer.seek(0));
		seek.addChangeListener(e -> { if (!updating && !seek.getValueIsAdjusting()) { viewer.seek(duration * seek.getValue() / 10000.0); } });
		speed.addActionListener(e -> viewer.speed(new double[]{.25, .5, 1, 2, 4}[speed.getSelectedIndex()]));
		loop.addActionListener(e -> viewer.loop(loop.isSelected()));
		mode.addActionListener(e -> viewer.miniature(mode.getSelectedIndex() == 0));
		isolate.addActionListener(e -> viewer.hideSurroundings(isolate.isSelected()));
		place.setEnabled(false); play.setEnabled(false); clear.setEnabled(false); seek.setEnabled(false);
	}

	void update(String filename, String message, String detail, double elapsed, double total,
		boolean playing, boolean ready, boolean placed, boolean house, boolean loading)
	{
		int extension = filename.lastIndexOf('.');
		String shortName = filename.length() > 64 ? filename.substring(0, 42) + "..." + (extension > 42 ? filename.substring(extension) : "") : filename;
		if (!filename.isEmpty()) { setText(file, shortName); if (!filename.equals(file.getToolTipText())) { file.setToolTipText(filename); } }
		setText(status, message.isEmpty() || detail.isEmpty() ? message + detail : message + "\n" + detail); duration = total;
		String playText = playing ? "Pause" : "Play";
		if (!playText.equals(play.getText())) { play.setText(playText); }
		play.setEnabled(ready && placed && !loading); place.setEnabled(ready && house && !loading);
		clear.setEnabled(placed); seek.setEnabled(ready && !loading);
		String timeText = format(elapsed) + " / " + format(total);
		if (!timeText.equals(time.getText())) { time.setText(timeText); }
		if (!seek.getValueIsAdjusting())
		{ updating = true; seek.setValue(total <= 0 ? 0 : (int) Math.round(10000 * elapsed / total)); updating = false; }
	}
	static String format(double seconds) { int s = (int) Math.max(0, seconds); return String.format("%d:%02d", s / 60, s % 60); }
	private static void setText(JTextArea area, String content)
	{ if (!content.equals(area.getText())) { area.setText(content); } }
	private static JPanel pair(JComponent left, JComponent right)
	{ JPanel row = new JPanel(new GridLayout(1, 2, 6, 0)); row.setOpaque(false); row.add(left); row.add(right); return row; }
	private static JTextArea text(String content)
	{
		JTextArea area = new JTextArea(content); area.setLineWrap(true); area.setWrapStyleWord(true); area.setEditable(false);
		// A read-only JTextArea still has a caret. Its default policy scrolls the
		// surrounding sidebar on setText(), even when the user scrolled elsewhere.
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		area.setFocusable(false);
		area.setOpaque(false); area.setFont(new JLabel().getFont()); area.setColumns(20); area.setRows(2); return area;
	}
}
