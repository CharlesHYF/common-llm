// 示例：流式生成并逐段输出文本增量
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
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

	request := &core.ModelRequest{
		Provider: "anthropic",
		Messages: []core.Message{core.TextMessage(core.RoleUser, "用三句话介绍上海")},
	}

	for event := range client.Stream(ctx, request) {
		switch event.Type {
		case core.ModelEventTextDelta:
			fmt.Print(event.Text)
		case core.ModelEventError:
			fmt.Fprintln(os.Stderr, "\n流式调用失败：", event.Error.Category, event.Error.Message)
			os.Exit(1)
		case core.ModelEventCompleted:
			fmt.Println()
			fmt.Println("finishReason:", event.Response.FinishReason)
		}
	}
}
