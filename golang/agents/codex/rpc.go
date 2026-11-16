// Codex App Server 的 JSON-RPC 消息结构、请求发送与响应等待
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package codex

import (
	"bytes"
	"context"
	"encoding/json"
	"strconv"
	"time"

	"github.com/CharlesHYF/common-llm/golang/config"
	"github.com/CharlesHYF/common-llm/golang/core"
	"github.com/CharlesHYF/common-llm/golang/internal/agentruntime"
)

// JSON-RPC 方法名
const (
	methodInitialize = "initialize"
)

// rpcMessage App Server 消息，省略 jsonrpc 字段
type rpcMessage struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *rpcError       `json:"error,omitempty"`
}

// rpcError JSON-RPC 错误对象
type rpcError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

// isResponse 判断是否为对客户端请求的响应
func (m *rpcMessage) isResponse() bool {
	return len(m.ID) > 0 && m.Method == ""
}

// isServerRequest 判断是否为服务端发起的请求
func (m *rpcMessage) isServerRequest() bool {
	return len(m.ID) > 0 && m.Method != ""
}

// rpcConnection 基于子进程标准输入输出的 JSON-RPC 连接，只在单个协程内使用
type rpcConnection struct {
	process  *agentruntime.Process
	nextID   int64
	provider string
}

// newRPCConnection 创建连接，请求 ID 从 1 开始
func newRPCConnection(process *agentruntime.Process) *rpcConnection {
	return &rpcConnection{
		process:  process,
		nextID:   1,
		provider: core.AgentCodex,
	}
}

// sendRequest 发送请求并返回请求 ID
func (c *rpcConnection) sendRequest(method string, params any) (string, error) {
	requestID := c.nextID
	c.nextID++

	payload, err := json.Marshal(map[string]any{
		"id":     requestID,
		"method": method,
		"params": params,
	})
	if err != nil {
		return "", core.WrapError(core.CategoryInternalError, "JSON-RPC 请求序列化失败："+method, c.provider, err)
	}

	if err := c.process.WriteLine(payload); err != nil {
		return "", core.WrapError(core.CategoryCLIProcessFailed, "向 App Server 写入请求失败："+method, c.provider, err)
	}

	return strconv.FormatInt(requestID, 10), nil
}

// sendNotification 发送不需要响应的通知
func (c *rpcConnection) sendNotification(method string) error {
	payload, err := json.Marshal(map[string]any{"method": method})
	if err != nil {
		return core.WrapError(core.CategoryInternalError, "JSON-RPC 通知序列化失败："+method, c.provider, err)
	}

	if err := c.process.WriteLine(payload); err != nil {
		return core.WrapError(core.CategoryCLIProcessFailed, "向 App Server 写入通知失败："+method, c.provider, err)
	}

	return nil
}

// sendResult 回复服务端请求，原样带回服务端的 id
func (c *rpcConnection) sendResult(requestID json.RawMessage, result any) error {
	payload, err := json.Marshal(map[string]any{
		"id":     requestID,
		"result": result,
	})
	if err != nil {
		return core.WrapError(core.CategoryInternalError, "JSON-RPC 响应序列化失败", c.provider, err)
	}

	return c.process.WriteLine(payload)
}

// sendErrorResult 以 JSON-RPC 错误回复不支持的服务端请求
func (c *rpcConnection) sendErrorResult(requestID json.RawMessage, message string) error {
	payload, err := json.Marshal(map[string]any{
		"id": requestID,
		"error": map[string]any{
			"code":    -32601,
			"message": message,
		},
	})
	if err != nil {
		return core.WrapError(core.CategoryInternalError, "JSON-RPC 错误响应序列化失败", c.provider, err)
	}

	return c.process.WriteLine(payload)
}

// matchesRequestID 比较消息 id 与请求 id，兼容数字与字符串形式
func matchesRequestID(rawID json.RawMessage, requestID string) bool {
	trimmed := string(bytes.Trim(bytes.TrimSpace(rawID), `"`))
	return trimmed == requestID
}

// parseRPCLine 解析一行 JSON-RPC 消息
func parseRPCLine(line []byte) (*rpcMessage, error) {
	var message rpcMessage
	if err := json.Unmarshal(line, &message); err != nil {
		return nil, err
	}

	return &message, nil
}

// buildInitializeParams 组装 initialize 参数，开启实验性 API 以接收完整通知
func buildInitializeParams() map[string]any {
	return map[string]any{
		"clientInfo": map[string]any{
			"name":    "common_llm",
			"title":   "common-llm",
			"version": core.Version,
		},
		"capabilities": map[string]any{
			"experimentalApi": true,
		},
	}
}

// probeHandshake 启动 App Server 完成 initialize 握手后关闭，用于 capabilities 探测
func probeHandshake(ctx context.Context, executablePath string, env []string, agentConfig config.AgentConfig) error {
	process, err := agentruntime.StartProcess(agentruntime.ProcessOptions{
		ExecutablePath: executablePath,
		Args:           []string{commandAppServer, flagListen, listenStdio},
		Env:            env,
		KillGrace:      agentruntime.SecondsToDuration(agentConfig.KillGraceSeconds),
		MaxLineBytes:   agentConfig.MaxLineBytes,
		MaxOutputBytes: agentConfig.MaxOutputBytes,
		MaxStderrBytes: agentConfig.MaxStderrBytes,
		Provider:       core.AgentCodex,
	})
	if err != nil {
		return err
	}

	defer func() {
		process.CloseStdin()
		if _, hasExited := process.WaitTimeout(2 * time.Second); !hasExited {
			process.Terminate()
		}

		agentruntime.DrainLines(process.Lines())
	}()

	connection := newRPCConnection(process)
	requestID, err := connection.sendRequest(methodInitialize, buildInitializeParams())
	if err != nil {
		return core.WrapError(core.CategoryCLIProtocolError, "App Server initialize 握手失败", core.AgentCodex, err)
	}

	for {
		select {
		case lineResult, isOpen := <-process.Lines():
			if !isOpen {
				return core.NewError(core.CategoryCLIProtocolError, "App Server 在 initialize 握手完成前退出", core.AgentCodex)
			}

			if lineResult.Err != nil {
				return lineResult.Err
			}

			message, parseErr := parseRPCLine(lineResult.Line)
			if parseErr != nil || !message.isResponse() || !matchesRequestID(message.ID, requestID) {
				continue
			}

			if message.Error != nil {
				return core.NewError(core.CategoryCLIProtocolError, "App Server initialize 返回错误："+message.Error.Message, core.AgentCodex)
			}

			return nil
		case <-ctx.Done():
			return agentruntime.WaitForContext(ctx, core.AgentCodex)
		}
	}
}
