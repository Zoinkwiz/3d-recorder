package com.extrinsiccognition.embertide;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

public final class EmbertidePanel extends PluginPanel
{
	private final JTextArea status = text("");
	private final JPanel files = new JPanel();
	private final JButton toggle = new JButton("Stop recording");
	private final JButton folder = new JButton("Show my recordings");
	private final JButton retry = new JButton("Retry saving");
	private final JButton discard = new JButton("Discard unsaved recording");
	private final Timer refresh;
	private boolean recording;
	private String drawn = "";
	private final EmbertidePlugin plugin;

	EmbertidePanel(EmbertidePlugin plugin)
	{
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);
		JLabel title = new JLabel("3D Replay Recorder");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		top.add(title);
		top.add(Box.createVerticalStrut(6));
		JTextArea where = text("Play as normal. When you're done, open your session in the Embertide app or at");
		top.add(where);
		JLabel studio = new JLabel("<html><u>embertide.gg/studio</u></html>");
		studio.setForeground(ColorScheme.BRAND_ORANGE);
		studio.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		studio.setToolTipText("Opens Studio in your browser. Drag a recording from your recordings folder into it.");
		studio.setAlignmentX(LEFT_ALIGNMENT);
		studio.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(MccrFileRecorder.STUDIO_URL);
			}
		});
		top.add(studio);
		add(top, BorderLayout.NORTH);

		JPanel middle = new JPanel();
		middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
		middle.setOpaque(false);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		middle.add(status);
		files.setLayout(new BoxLayout(files, BoxLayout.Y_AXIS));
		files.setOpaque(false);
		files.setAlignmentX(LEFT_ALIGNMENT);
		middle.add(files);
		middle.add(Box.createVerticalGlue());
		add(middle, BorderLayout.CENTER);

		JPanel actions = new JPanel(new GridLayout(0, 1, 0, 6));
		actions.setOpaque(false);
		toggle.addActionListener(e -> {
			if (recording)
			{
				plugin.stopRecording();
			}
			else
			{
				plugin.startRecording();
			}
		});
		folder.addActionListener(e -> {
			JTextArea path = new JTextArea(plugin.folderPath());
			path.setEditable(false);
			path.setLineWrap(true);
			path.setColumns(40);
			path.setRows(3);
			path.selectAll();
			javax.swing.JOptionPane.showMessageDialog(this, path, "Your recordings are here", javax.swing.JOptionPane.INFORMATION_MESSAGE);
		});
		folder.setToolTipText("Shows where your recordings are saved, so you can find them or drag one into Studio.");
		retry.addActionListener(e -> plugin.retrySave());
		discard.addActionListener(e -> {
			if (javax.swing.JOptionPane.showConfirmDialog(this,
				"Throw away the recording that failed to save? You can't get it back.", "Discard recording",
				javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION)
			{
				plugin.discardSave();
			}
		});
		actions.add(toggle);
		actions.add(retry);
		actions.add(discard);
		actions.add(folder);
		JPanel bottom = new JPanel();
		bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
		bottom.setOpaque(false);
		actions.setAlignmentX(LEFT_ALIGNMENT);
		bottom.add(actions);
		bottom.add(Box.createVerticalStrut(16));
		JComponent watch = plugin.viewer().panel();
		watch.setAlignmentX(LEFT_ALIGNMENT);
		bottom.add(watch);
		add(bottom, BorderLayout.SOUTH);

		this.plugin = plugin;
		refresh = new Timer(500, e -> render(plugin));
		refresh.setInitialDelay(0);
		render(plugin);
	}

	private void render(EmbertidePlugin plugin)
	{
		EmbertidePlugin.PanelState state = plugin.panelState();
		if (!state.text.equals(status.getText()))
		{
			status.setText(state.text);
		}
		recording = state.recording;
		toggle.setText(recording ? "Stop recording" : "Start recording");
		toggle.setEnabled(state.canToggle);
		retry.setVisible(plugin.canRecover());
		discard.setVisible(plugin.canRecover());
		drawFiles(state.current, state.saved);
	}

	private void drawFiles(String current, List<EmbertidePlugin.SavedFile> saved)
	{
		StringBuilder key = new StringBuilder(current);
		for (EmbertidePlugin.SavedFile file : saved)
		{
			key.append('|').append(file.name).append(file.problem);
		}
		if (key.toString().equals(drawn))
		{
			return;
		}
		drawn = key.toString();
		files.removeAll();
		if (current.isEmpty() && saved.isEmpty())
		{
			files.revalidate();
			files.repaint();
			return;
		}
		JLabel heading = new JLabel("This session");
		heading.setFont(heading.getFont().deriveFont(Font.BOLD));
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		heading.setAlignmentX(LEFT_ALIGNMENT);
		files.add(heading);
		files.add(Box.createVerticalStrut(4));
		if (!current.isEmpty())
		{
			files.add(row(current, "recording now", false, null));
		}
		for (int i = saved.size() - 1; i >= 0; i--)
		{
			EmbertidePlugin.SavedFile file = saved.get(i);
			int seconds = (int) file.seconds;
			String note = file.problem.isEmpty()
				? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
				: "not saved: " + file.problem;
			files.add(row(file.name, note, !file.problem.isEmpty(), file.problem.isEmpty() ? () -> plugin.viewer().load(file.name) : null));
		}
		files.revalidate();
		files.repaint();
	}

	private static JPanel row(String name, String note, boolean problem, Runnable watch)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		JLabel file = new JLabel(name);
		file.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		file.setToolTipText(name);
		JLabel detail = new JLabel(note);
		detail.setFont(detail.getFont().deriveFont(detail.getFont().getSize2D() - 1f));
		detail.setForeground(problem ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
		row.add(file);
		row.add(detail);
		if (watch != null)
		{
			JLabel link = new JLabel("<html><u>Watch in your house</u></html>");
			link.setForeground(ColorScheme.BRAND_ORANGE);
			link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			link.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					watch.run();
				}
			});
			row.add(link);
		}
		return row;
	}

	/** Read-only text whose caret never scrolls the sidebar when the text changes. */
	private static JTextArea text(String content)
	{
		JTextArea area = new JTextArea(content);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		area.setAlignmentX(LEFT_ALIGNMENT);
		((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		return area;
	}

	@Override
	public void onActivate()
	{
		refresh.start();
	}

	@Override
	public void onDeactivate()
	{
		refresh.stop();
	}

	void dispose()
	{
		refresh.stop();
	}
}
