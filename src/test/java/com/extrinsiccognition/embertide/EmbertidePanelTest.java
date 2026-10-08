package com.extrinsiccognition.embertide;

import com.extrinsiccognition.embertide.viewer.Viewer;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** The sidebar scrolls the recorder's panel with the Watch section in it; its refresh must not move that scroll. */
public class EmbertidePanelTest
{
	@Test
	public void statusRefreshesNeitherRewriteUnchangedTextNorScrollTheSidebar() throws Exception
	{
		EmbertidePlugin plugin = new EmbertidePlugin();
		Field viewer = EmbertidePlugin.class.getDeclaredField("viewer");
		viewer.setAccessible(true);
		viewer.set(plugin, new Viewer());
		Method render = EmbertidePanel.class.getDeclaredMethod("render", EmbertidePlugin.class);
		render.setAccessible(true);
		AtomicInteger changes = new AtomicInteger();
		SwingUtilities.invokeAndWait(() ->
		{
			EmbertidePanel panel = new EmbertidePanel(plugin);
			for (JTextArea area : areas(panel))
			{
				assertEquals(DefaultCaret.NEVER_UPDATE, ((DefaultCaret) area.getCaret()).getUpdatePolicy());
				assertFalse(area.isFocusable());
				area.getDocument().addDocumentListener(new DocumentListener()
				{
					public void insertUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void removeUpdate(DocumentEvent e) { changes.incrementAndGet(); }
					public void changedUpdate(DocumentEvent e) { changes.incrementAndGet(); }
				});
			}
			try { for (int i = 0; i < 30; i++) { render.invoke(panel, plugin); } }
			catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		});
		assertEquals("Refreshing the same status must not clear and reinsert it", 0, changes.get());
	}

	private static java.util.List<JTextArea> areas(Container parent)
	{
		java.util.List<JTextArea> found = new java.util.ArrayList<>();
		for (Component child : parent.getComponents())
		{
			if (child instanceof JTextArea) { found.add((JTextArea) child); }
			else if (child instanceof Container) { found.addAll(areas((Container) child)); }
		}
		return found;
	}
}
