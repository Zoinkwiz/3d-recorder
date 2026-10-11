package com.extrinsiccognition.embertide;

import java.lang.reflect.Field;
import javax.inject.Inject;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class PluginWiringTest
{
	@Test
	public void thePluginIsGivenRuneLitesHttpClient() throws Exception
	{
		Field field = EmbertidePlugin.class.getDeclaredField("okHttpClient");
		assertTrue("left null, the first capture with the live view on throws", field.isAnnotationPresent(Inject.class));
	}
}
