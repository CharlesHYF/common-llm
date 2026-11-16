package io.github.charleshyf.commonllm.config;

import io.github.charleshyf.commonllm.core.SecretMasker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 Coding Agent 的配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class AgentConfig {

	public static final String AGENT_CLAUDE_CODE = "claude_code";
	public static final String AGENT_CODEX = "codex";
	public static final String AUTH_MODE_API_KEY = "api_key";
	public static final String AUTH_MODE_CLI_EXISTING_SESSION = "cli_existing_session";
	public static final String AUTH_MODE_VENDOR_SUPPORTED = "vendor_supported";
	public static final double DEFAULT_TIMEOUT_SECONDS = 600;
	public static final String DEFAULT_PERMISSION_MODE = "default";
	public static final String DEFAULT_SANDBOX_MODE = "workspace-write";
	public static final int DEFAULT_MAX_CONCURRENT_RUNS = 4;
	public static final long DEFAULT_MAX_OUTPUT_BYTES = 10485760L;
	public static final int DEFAULT_MAX_LINE_BYTES = 8388608;
	public static final int DEFAULT_MAX_STDERR_BYTES = 65536;
	public static final int DEFAULT_MAX_BUFFERED_EVENTS = 10000;
	public static final int DEFAULT_MAX_TOOL_OUTPUT_CHARS = 4000;
	public static final double DEFAULT_KILL_GRACE_SECONDS = 5;

	private final String name;
	private final boolean isEnabled;
	private final String authMode;
	private final String apiKey;
	private final String executablePath;
	private final String homeDirectory;
	private final String workingDirectory;
	private final double timeoutSeconds;
	private final String permissionMode;
	private final List<String> allowedTools;
	private final String sandboxMode;
	private final List<String> envAllowlist;
	private final int maxConcurrentRuns;
	private final long maxOutputBytes;
	private final int maxLineBytes;
	private final int maxStderrBytes;
	private final int maxBufferedEvents;
	private final int maxToolOutputChars;
	private final double killGraceSeconds;
	private final boolean shouldCollectEvents;
	private final Map<String, Object> nativeOptions;

	private AgentConfig(Builder builder) {
		this.name = builder.name;
		this.isEnabled = builder.isEnabled;
		this.authMode = builder.authMode;
		this.apiKey = builder.apiKey;
		this.executablePath = builder.executablePath;
		this.homeDirectory = builder.homeDirectory;
		this.workingDirectory = builder.workingDirectory;
		this.timeoutSeconds = builder.timeoutSeconds;
		this.permissionMode = builder.permissionMode;
		this.allowedTools = Collections.unmodifiableList(new ArrayList<>(builder.allowedTools));
		this.sandboxMode = builder.sandboxMode;
		this.envAllowlist = Collections.unmodifiableList(new ArrayList<>(builder.envAllowlist));
		this.maxConcurrentRuns = builder.maxConcurrentRuns;
		this.maxOutputBytes = builder.maxOutputBytes;
		this.maxLineBytes = builder.maxLineBytes;
		this.maxStderrBytes = builder.maxStderrBytes;
		this.maxBufferedEvents = builder.maxBufferedEvents;
		this.maxToolOutputChars = builder.maxToolOutputChars;
		this.killGraceSeconds = builder.killGraceSeconds;
		this.shouldCollectEvents = builder.shouldCollectEvents;
		this.nativeOptions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.nativeOptions));
	}

	/**
	 * 返回填好默认值的构造器，executable_path 默认取 Agent 对应的命令名
	 */
	public static Builder builder(String name) {
		return new Builder(name);
	}

	public String getName() {
		return name;
	}

	public boolean isEnabled() {
		return isEnabled;
	}

	public String getAuthMode() {
		return authMode;
	}

	public String getApiKey() {
		return apiKey;
	}

	public String getExecutablePath() {
		return executablePath;
	}

	public String getHomeDirectory() {
		return homeDirectory;
	}

	public String getWorkingDirectory() {
		return workingDirectory;
	}

	public double getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public String getPermissionMode() {
		return permissionMode;
	}

	public List<String> getAllowedTools() {
		return allowedTools;
	}

	public String getSandboxMode() {
		return sandboxMode;
	}

	public List<String> getEnvAllowlist() {
		return envAllowlist;
	}

	public int getMaxConcurrentRuns() {
		return maxConcurrentRuns;
	}

	public long getMaxOutputBytes() {
		return maxOutputBytes;
	}

	public int getMaxLineBytes() {
		return maxLineBytes;
	}

	public int getMaxStderrBytes() {
		return maxStderrBytes;
	}

	public int getMaxBufferedEvents() {
		return maxBufferedEvents;
	}

	public int getMaxToolOutputChars() {
		return maxToolOutputChars;
	}

	public double getKillGraceSeconds() {
		return killGraceSeconds;
	}

	public boolean shouldCollectEvents() {
		return shouldCollectEvents;
	}

	public Map<String, Object> getNativeOptions() {
		return nativeOptions;
	}

	@Override
	public String toString() {
		return "AgentConfig{name=" + name + ", enabled=" + isEnabled + ", authMode=" + authMode + ", apiKey=" + SecretMasker.maskValue(apiKey) + ", executablePath=" + executablePath + ", workingDirectory=" + workingDirectory + ", timeoutSeconds=" + timeoutSeconds + ", permissionMode=" + permissionMode + ", sandboxMode=" + sandboxMode + "}";
	}

	/**
	 * AgentConfig 构造器
	 */
	public static final class Builder {

		private final String name;
		private boolean isEnabled;
		private String authMode = AUTH_MODE_CLI_EXISTING_SESSION;
		private String apiKey;
		private String executablePath;
		private String homeDirectory;
		private String workingDirectory;
		private double timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
		private String permissionMode = DEFAULT_PERMISSION_MODE;
		private List<String> allowedTools = new ArrayList<>();
		private String sandboxMode = DEFAULT_SANDBOX_MODE;
		private List<String> envAllowlist = new ArrayList<>();
		private int maxConcurrentRuns = DEFAULT_MAX_CONCURRENT_RUNS;
		private long maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
		private int maxLineBytes = DEFAULT_MAX_LINE_BYTES;
		private int maxStderrBytes = DEFAULT_MAX_STDERR_BYTES;
		private int maxBufferedEvents = DEFAULT_MAX_BUFFERED_EVENTS;
		private int maxToolOutputChars = DEFAULT_MAX_TOOL_OUTPUT_CHARS;
		private double killGraceSeconds = DEFAULT_KILL_GRACE_SECONDS;
		private boolean shouldCollectEvents;
		private Map<String, Object> nativeOptions = new LinkedHashMap<>();

		private Builder(String name) {
			this.name = name;
			this.executablePath = AGENT_CLAUDE_CODE.equals(name) ? "claude" : "codex";
		}

		public Builder enabled(boolean isEnabled) {
			this.isEnabled = isEnabled;
			return this;
		}

		public Builder authMode(String authMode) {
			this.authMode = authMode;
			return this;
		}

		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public Builder executablePath(String executablePath) {
			this.executablePath = executablePath;
			return this;
		}

		public Builder homeDirectory(String homeDirectory) {
			this.homeDirectory = homeDirectory;
			return this;
		}

		public Builder workingDirectory(String workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder timeoutSeconds(double timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
			return this;
		}

		public Builder permissionMode(String permissionMode) {
			this.permissionMode = permissionMode;
			return this;
		}

		public Builder allowedTools(List<String> allowedTools) {
			this.allowedTools = new ArrayList<>(allowedTools);
			return this;
		}

		public Builder sandboxMode(String sandboxMode) {
			this.sandboxMode = sandboxMode;
			return this;
		}

		public Builder envAllowlist(List<String> envAllowlist) {
			this.envAllowlist = new ArrayList<>(envAllowlist);
			return this;
		}

		public Builder maxConcurrentRuns(int maxConcurrentRuns) {
			this.maxConcurrentRuns = maxConcurrentRuns;
			return this;
		}

		public Builder maxOutputBytes(long maxOutputBytes) {
			this.maxOutputBytes = maxOutputBytes;
			return this;
		}

		public Builder maxLineBytes(int maxLineBytes) {
			this.maxLineBytes = maxLineBytes;
			return this;
		}

		public Builder maxStderrBytes(int maxStderrBytes) {
			this.maxStderrBytes = maxStderrBytes;
			return this;
		}

		public Builder maxBufferedEvents(int maxBufferedEvents) {
			this.maxBufferedEvents = maxBufferedEvents;
			return this;
		}

		public Builder maxToolOutputChars(int maxToolOutputChars) {
			this.maxToolOutputChars = maxToolOutputChars;
			return this;
		}

		public Builder killGraceSeconds(double killGraceSeconds) {
			this.killGraceSeconds = killGraceSeconds;
			return this;
		}

		public Builder collectEvents(boolean shouldCollectEvents) {
			this.shouldCollectEvents = shouldCollectEvents;
			return this;
		}

		public Builder nativeOptions(Map<String, Object> nativeOptions) {
			this.nativeOptions = new LinkedHashMap<>(nativeOptions);
			return this;
		}

		public AgentConfig build() {
			return new AgentConfig(this);
		}
	}
}
