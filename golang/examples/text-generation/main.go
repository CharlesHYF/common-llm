// 示例：读取 common-llm.yaml 并发起一次非流式文本生成
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
	"errors"
	"fmt"
	"os"

	commonllm "github.com/CharlesHYF/common-llm/golang"
	"github.com/CharlesHYF/common-llm/golang/core"
)

func main() {
	ctx := context.Background()

	client, err := commonllm.Create(ctx, commonllm.Options{})
	if err != nil {
		fmt.Fprintln(os.Stderr, "加载配置失败：", err)
		os.Exit(1)
	}
	defer client.Close()

	response, err := client.Generate(ctx, &core.ModelRequest{
		Provider: "openai",
		Messages: []core.Message{
			core.TextMessage(core.RoleSystem, "你是天气助手"),
			core.TextMessage(core.RoleUser, "上海今天天气如何"),
		},
	})
	if err != nil {
		var commonErr *commonllm.Error
		if errors.As(err, &commonErr) {
			fmt.Fprintln(os.Stderr, "调用失败，分类：", commonErr.Info.Category, "可重试：", commonErr.Info.Retriable)
		}

		os.Exit(1)
	}

	fmt.Println(response.Text)
	fmt.Println("finishReason:", response.FinishReason)
}
