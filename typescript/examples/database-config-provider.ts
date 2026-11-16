/**
 * 示例：把配置表映射为 ConfigProvider，用内存数组代替数据库，reload 时重新读取
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */

import { CommonLlm } from "@charleshyf/common-llm";

import type { CommonLlmConfigInput, ConfigProvider, ProviderConfigInput } from "@charleshyf/common-llm";

interface ProviderConfigRow {
	instanceName: string;
	providerType: "openai" | "anthropic" | "gemini";
	isEnabled: boolean;
	credentialReference: string;
	defaultModel: string;
}

const providerRows: ProviderConfigRow[] = [
	{
		instanceName: "openai",
		providerType: "openai",
		isEnabled: true,
		credentialReference: "env:OPENAI_API_KEY",
		defaultModel: process.env.OPENAI_MODEL ?? "",
	},

	{
		instanceName: "anthropic",
		providerType: "anthropic",
		isEnabled: false,
		credentialReference: "env:ANTHROPIC_API_KEY",
		defaultModel: process.env.ANTHROPIC_MODEL ?? "",
	},
];

/**
 * 按凭证引用解析密钥明文，示例只支持 env: 前缀，实际项目可替换为 KMS 客户端
 */
function resolveCredential(reference: string): string {
	if (reference.startsWith("env:")) {
		return process.env[reference.slice("env:".length)] ?? "";
	}

	return "";
}

class InMemoryTableConfigProvider implements ConfigProvider {
	private readonly rows: readonly ProviderConfigRow[];

	constructor(rows: readonly ProviderConfigRow[]) {
		this.rows = rows;
	}

	async load(): Promise<CommonLlmConfigInput> {
		const providers: Record<string, ProviderConfigInput> = {};

		for (const row of this.rows) {
			providers[row.instanceName] = {
				type: row.providerType,
				enabled: row.isEnabled,
				api_key: resolveCredential(row.credentialReference),
				default_model: row.defaultModel,
				native_options: row.providerType === "anthropic"
					? {
						max_tokens: 1024,
					}
					: {},
			};
		}

		return {
			providers,
		};
	}
}

const llm = await CommonLlm.create({
	configProvider: new InMemoryTableConfigProvider(providerRows),
});

try {
	console.log(JSON.stringify(llm.config));
	await llm.reload();
	console.log(llm.listProviders());
} finally {
	await llm.close();
}
