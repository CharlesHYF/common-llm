// Windows 平台的子进程结束方式
// 创建日期：2026-11-16
// 修改日期：2026-11-16

//go:build windows

package agentruntime

import (
	"os/exec"
)

// configureProcessGroup Windows 下不创建进程组，结束时直接结束进程
func configureProcessGroup(cmd *exec.Cmd) {
}

// signalTerminate Windows 没有 SIGTERM，直接结束进程
func signalTerminate(cmd *exec.Cmd) {
	signalKill(cmd)
}

// signalKill 结束进程
func signalKill(cmd *exec.Cmd) {
	if cmd.Process == nil {
		return
	}

	_ = cmd.Process.Kill()
}

// signalKillGroupAfterExit Windows 下主进程已退出时无需额外处理
func signalKillGroupAfterExit(cmd *exec.Cmd) {
}
