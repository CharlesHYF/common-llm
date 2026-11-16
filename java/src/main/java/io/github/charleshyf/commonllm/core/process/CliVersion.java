package io.github.charleshyf.commonllm.core.process;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析与比较 CLI 输出中的语义化版本号
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class CliVersion implements Comparable<CliVersion> {

	private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

	private final int major;
	private final int minor;
	private final int patch;

	public CliVersion(int major, int minor, int patch) {
		this.major = major;
		this.minor = minor;
		this.patch = patch;
	}

	/**
	 * 从命令输出中解析第一个 x.y.z 版本号，找不到返回 null
	 */
	public static CliVersion parse(String output) {
		if (output == null) {
			return null;
		}

		Matcher matcher = VERSION_PATTERN.matcher(output);

		if (!matcher.find()) {
			return null;
		}

		return new CliVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
	}

	@Override
	public int compareTo(CliVersion other) {
		if (major != other.major) {
			return Integer.compare(major, other.major);
		}

		if (minor != other.minor) {
			return Integer.compare(minor, other.minor);
		}

		return Integer.compare(patch, other.patch);
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof CliVersion)) {
			return false;
		}

		return compareTo((CliVersion) other) == 0;
	}

	@Override
	public int hashCode() {
		return toString().hashCode();
	}

	@Override
	public String toString() {
		return major + "." + minor + "." + patch;
	}
}
