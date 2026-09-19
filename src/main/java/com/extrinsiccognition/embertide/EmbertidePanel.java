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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The side panel: where the memories go, what this one is doing, the files
 * this session has written, and the things a player can ask of it.
 */
public final class EmbertidePanel extends PluginPanel
{
	private final JTextArea status = new JTextArea();
	private final JPanel files = new JPanel();
	private final JButton toggle = new JButton("Stop recording");
	private final JButton folder = new JButton("Show the folder");
	private final Timer refresh;
	private boolean recording;
	/** What the file list last drew, so it is rebuilt only when a file changes. */
	private String drawn = "";

	EmbertidePanel(EmbertidePlugin plugin)
	{
		super(false);
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setOpaque(false);
		JLabel title = new JLabel("Embertide");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		top.add(title);
		top.add(Box.createVerticalStrut(6));
		// The first thing on the panel is where the memories are for: the app
		// reads the folder itself, and the web Studio takes a file dragged in.
		// Wrapped text and the link on its own line, so nothing is clipped at
		// the panel's width.
		JTextArea where = new JTextArea("Edit your clips in the Embertide app, or at");
		where.setEditable(false);
		where.setLineWrap(true);
		where.setWrapStyleWord(true);
		where.setOpaque(false);
		where.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		where.setAlignmentX(LEFT_ALIGNMENT);
		top.add(where);
		JLabel studio = new JLabel("<html><u>embertide.gg/studio</u></html>");
		studio.setForeground(ColorScheme.BRAND_ORANGE);
		studio.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		studio.setToolTipText("Open Embertide Studio in your browser, then drag a file from the folder into it.");
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
		status.setEditable(false);
		status.setLineWrap(true);
		status.setWrapStyleWord(true);
		status.setOpaque(false);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		status.setAlignmentX(LEFT_ALIGNMENT);
		middle.add(status);
		files.setLayout(new BoxLayout(files, BoxLayout.Y_AXIS));
		files.setOpaque(false);
		files.setAlignmentX(LEFT_ALIGNMENT);
		middle.add(files);
		middle.add(Box.createVerticalGlue());
		add(middle, BorderLayout.CENTER);

		JPanel actions = new JPanel(new GridLayout(2, 1, 0, 6));
		actions.setOpaque(false);
		// The first question a player has is whether this is recording right now,
		// and the answer has to be a button they can press, not a line of text.
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
		folder.addActionListener(e -> plugin.showFolder());
		folder.setToolTipText("Open the folder the files are in.");
		actions.add(toggle);
		actions.add(folder);
		add(actions, BorderLayout.SOUTH);

		refresh = new Timer(500, e -> render(plugin));
		refresh.setInitialDelay(0);
		render(plugin);
	}

	private void render(EmbertidePlugin plugin)
	{
		EmbertidePlugin.PanelState state = plugin.panelState();
		status.setText(state.text);
		recording = state.recording;
		toggle.setText(recording ? "Stop recording" : "Start recording");
		toggle.setEnabled(state.canToggle);
		drawFiles(state.current, state.saved);
	}

	/** The files of this session, newest first, with the one being written on top. */
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
			files.add(row(current, "recording now", false));
		}
		for (int i = saved.size() - 1; i >= 0; i--)
		{
			EmbertidePlugin.SavedFile file = saved.get(i);
			int seconds = (int) file.seconds;
			String note = file.problem.isEmpty()
				? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
				: "not saved: " + file.problem;
			files.add(row(file.name, note, !file.problem.isEmpty()));
		}
		files.revalidate();
		files.repaint();
	}

	private static JPanel row(String name, String note, boolean problem)
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
		return row;
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
