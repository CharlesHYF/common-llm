package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 对日志、异常与配置输出中的密钥做脱敏
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class SecretMasker {

	public static final String MASK = "***";

	// 短于该长度的值不登记，避免把普通单词当成密钥替换
	private static final int MIN_SECRET_LENGTH = 6;

	private static final Set<String> REGISTERED_SECRETS = ConcurrentHashMap.newKeySet();

	private static final List<Pattern> SECRET_PATTERNS = buildSecretPatterns();

	private SecretMasker() {
	}

	/**
	 * 登记配置中出现的密钥明文，之后的脱敏会替换该值
	 */
	public static void registerSecret(String secret) {
		if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
			return;
		}

		REGISTERED_SECRETS.add(secret);
	}

	/**
	 * 替换文本中的已登记密钥与常见密钥格式
	 */
	public static String mask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		String maskedText = text;

		for (String secret : REGISTERED_SECRETS) {
			maskedText = maskedText.replace(secret, MASK);
		}

		for (Pattern pattern : SECRET_PATTERNS) {
			maskedText = pattern.matcher(maskedText).replaceAll("$1" + MASK);
		}

		return maskedText;
	}

	/**
	 * 返回密钥字段的展示值，非空一律输出掩码
	 */
	public static String maskValue(String secret) {
		if (secret == null) {
			return null;
		}

		return MASK;
	}

	private static List<Pattern> buildSecretPatterns() {
		List<Pattern> patterns = new ArrayList<>();
		patterns.add(Pattern.compile("(sk-(?:ant-)?)[A-Za-z0-9_\\-*]{8,}"));
		patterns.add(Pattern.compile("(AIza)[A-Za-z0-9_\\-]{20,}"));
		patterns.add(Pattern.compile("((?i)bearer\\s+)[A-Za-z0-9._\\-]{8,}"));
		patterns.add(Pattern.compile("((?i)(?:api[_-]?key|x-api-key|token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]{6,}"));
		return patterns;
	}
}
