package com.extrinsiccognition.embertide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Opaque salted key for the local account that made a recording. */
final class AccountKey
{
	static final String SALT_KEY = "accountSalt";
	private static final int KEY_CHARS = 32;

	private AccountKey()
	{
	}

	/** Null when logged out. */
	static String of(String salt, long accountHash)
	{
		if (accountHash == -1 || salt == null || salt.isEmpty())
		{
			return null;
		}
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((salt + ":" + accountHash).getBytes(StandardCharsets.UTF_8));
			return hex(hash).substring(0, KEY_CHARS);
		}
		catch (NoSuchAlgorithmException e)
		{
			return null;
		}
	}

	static String newSalt()
	{
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return hex(bytes);
	}

	private static String hex(byte[] bytes)
	{
		StringBuilder out = new StringBuilder(bytes.length * 2);
		for (byte b : bytes)
		{
			out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
		}
		return out.toString();
	}
}
