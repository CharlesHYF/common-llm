// 示例：同时配置官方 OpenAI 与 OpenAI 兼容端点，按 provider 实例名切换调用
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
	"fmt"
	"os"

	commonllm "github.com/CharlesHYF/common-llm/golang"
	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
)

func main() {
	ctx := context.Background()
	overrides := config.Document{}
	overrides.SetPath("providers.openai.enabled", true)
	overrides.SetPath("providers.openai.api_key", os.Getenv("OPENAI_API_KEY"))
	overrides.SetPath("providers.openai.default_model", os.Getenv("OPENAI_MODEL"))
	overrides.SetPath("providers.local_vllm.type", "openai")
	overrides.SetPath("providers.local_vllm.api", "chat_completions")
	overrides.SetPath("providers.local_vllm.enabled", true)
	overrides.SetPath("providers.local_vllm.api_key", "none")
	overrides.SetPath("providers.local_vllm.base_url", "http://127.0.0.1:8000/v1")
	overrides.SetPath("providers.local_vllm.default_model", os.Getenv("LOCAL_VLLM_MODEL"))

	client, err := commonllm.Create(ctx, commonllm.Options{Overrides: overrides})
	if err != nil {
		fmt.Fprintln(os.Stderr, "配置非法：", err)
		os.Exit(1)
	}
	defer client.Close()

	for _, capability := range client.ListProviders() {
		fmt.Println("已启用：", capability.Provider, capability.Features)
	}

	for _, provider := range []string{"openai", "local_vllm"} {
		response, err := client.Generate(ctx, &core.ModelRequest{
			Provider: provider,
			Messages: []core.Message{core.TextMessage(core.RoleUser, "只回复 ok")},
		})
		if err != nil {
			fmt.Fprintln(os.Stderr, provider, "调用失败：", err)
			continue
		}

		fmt.Println(provider, "回复：", response.Text)
	}
}
