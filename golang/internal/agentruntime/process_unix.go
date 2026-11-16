// Unix 平台的进程组创建与整组信号发送
// 创建日期：2026-11-16
// 修改日期：2026-11-16

//go:build !windows

package agentruntime

import (
	"os/exec"
	"syscall"
)

// configureProcessGroup 让子进程成为新进程组的组长，便于整组结束
func configureProcessGroup(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

// signalTerminate 向整个进程组发送 SIGTERM
func signalTerminate(cmd *exec.Cmd) {
	signalGroup(cmd, syscall.SIGTERM)
}

// signalKill 向整个进程组发送 SIGKILL
func signalKill(cmd *exec.Cmd) {
	signalGroup(cmd, syscall.SIGKILL)
}

// signalKillGroupAfterExit 主进程退出后结束组内残留的子孙进程
func signalKillGroupAfterExit(cmd *exec.Cmd) {
	signalGroup(cmd, syscall.SIGKILL)
}

// signalGroup 以负 pid 向进程组发送信号，进程组不存在时忽略错误
func signalGroup(cmd *exec.Cmd, signal syscall.Signal) {
	if cmd.Process == nil {
		return
	}

	_ = syscall.Kill(-cmd.Process.Pid, signal)
}
