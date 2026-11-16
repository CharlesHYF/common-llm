// 示例：调用 Coding Agent 执行任务并处理 Codex 审批请求
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	commonllm "github.com/CharlesHYF/common-llm/golang"
	"github.com/CharlesHYF/common-llm/golang/core"
)

func main() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	client, err := commonllm.Create(ctx, commonllm.Options{})
	if err != nil {
		fmt.Fprintln(os.Stderr, "加载配置失败：", err)
		os.Exit(1)
	}
	defer client.Close()

	agent, err := client.Agent(core.AgentCodex)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Agent 未启用：", err)
		os.Exit(1)
	}

	request := &core.AgentRequest{
		Agent:  core.AgentCodex,
		Prompt: "读取 README.md，用一句话总结",
		// 只批准只读命令，其余一律拒绝
		ApprovalHandler: func(ctx context.Context, approval core.ApprovalRequest) (core.ApprovalDecision, error) {
			if approval.Kind == core.ApprovalKindCommand && strings.Contains(approval.Summary, "cat ") {
				return core.DecisionApprove, nil
			}

			return core.DecisionDecline, nil
		},
	}

	for event := range agent.RunStream(ctx, request) {
		switch event.Type {
		case core.AgentEventMessage:
			if !event.IsDelta {
				fmt.Println("assistant:", event.Text)
			}
		case core.AgentEventToolStarted:
			fmt.Println("工具开始：", *event.ToolName)
		case core.AgentEventCompleted:
			fmt.Println("完成，sessionId 可用于 Resume：", *event.Result.SessionID)
		case core.AgentEventFailed, core.AgentEventCancelled:
			fmt.Fprintln(os.Stderr, "run 未完成：", event.Result.Status, event.Result.Error.Message)
			os.Exit(1)
		}
	}
}
