package com.extrinsiccognition.embertide;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AttackerTest
{
	private final Object me = new Object();
	private final Object boss = new Object();
	private final Object other = new Object();
	private final Object third = new Object();
	private final Map<Object, Object> targets = new IdentityHashMap<>();

	private Object attacker(Object mine, List<Object> present)
	{
		return OsrsCapture.likelyAttacker(me, mine, present, targets::get);
	}

	@Test
	public void ownTargetTargetingBackWins()
	{
		targets.put(boss, me);
		targets.put(other, me);
		assertSame(boss, attacker(boss, Arrays.asList(me, boss, other)));
	}

	@Test
	public void singleActorTargetingThePlayerWins()
	{
		targets.put(other, me);
		assertSame(other, attacker(boss, Arrays.asList(me, boss, other)));
	}

	@Test
	public void fallsBackToOwnTargetWhenNobodyTargetsThePlayer()
	{
		targets.put(boss, third);
		assertSame(boss, attacker(boss, Arrays.asList(me, boss, third)));
		assertSame(boss, attacker(boss, Collections.emptyList()));
	}

	@Test
	public void severalAttackersAreAmbiguous()
	{
		targets.put(other, me);
		targets.put(third, me);
		assertNull(attacker(boss, Arrays.asList(me, boss, other, third)));
	}

	@Test
	public void nobodyAtAll()
	{
		assertNull(attacker(null, Arrays.asList(me, boss)));
	}
}
