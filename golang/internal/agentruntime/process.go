// Agent CLI 子进程的启动、按行读取标准输出、stderr 末尾保留与整组结束
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package agentruntime

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// unknownExitCode 进程未正常退出时的退出码
const unknownExitCode = -1

// ProcessOptions 子进程启动参数，参数以数组传递，不经过 shell。
type ProcessOptions struct {
	ExecutablePath string
	Args           []string
	Dir            string
	Env            []string
	KillGrace      time.Duration
	MaxLineBytes   int64
	MaxOutputBytes int64
	MaxStderrBytes int64
	Provider       string
}

// LineResult 标准输出的一行或读取错误，读取错误之后不再有新行。
type LineResult struct {
	Line []byte
	Err  *core.Error
}

// Process 运行中的子进程。
type Process struct {
	cmd           *exec.Cmd
	stdin         io.WriteCloser
	stdinMutex    sync.Mutex
	isStdinClosed bool
	lines         chan LineResult
	stderrTail    *tailBuffer
	stderrDone    chan struct{}
	waitDone      chan struct{}
	exitCode      int
	killGrace     time.Duration
	terminateOnce sync.Once
}

// StartProcess 启动子进程，Unix 下放入独立进程组以便整组结束。
func StartProcess(options ProcessOptions) (*Process, error) {
	cmd := exec.Command(options.ExecutablePath, options.Args...)
	cmd.Dir = options.Dir
	cmd.Env = options.Env
	configureProcessGroup(cmd)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, core.WrapError(core.CategoryCLIProcessFailed, "创建子进程标准输入失败", options.Provider, err)
	}

	stdoutReader, stdoutWriter, err := os.Pipe()
	if err != nil {
		return nil, core.WrapError(core.CategoryCLIProcessFailed, "创建子进程标准输出失败", options.Provider, err)
	}

	stderrReader, stderrWriter, err := os.Pipe()
	if err != nil {
		closeFiles(stdoutReader, stdoutWriter)
		return nil, core.WrapError(core.CategoryCLIProcessFailed, "创建子进程标准错误失败", options.Provider, err)
	}

	cmd.Stdout = stdoutWriter
	cmd.Stderr = stderrWriter

	if err := cmd.Start(); err != nil {
		closeFiles(stdoutReader, stdoutWriter, stderrReader, stderrWriter)
		return nil, classifyStartError(err, options)
	}

	// 父进程关闭写端，所有子孙进程退出后读端才会收到 EOF
	closeFiles(stdoutWriter, stderrWriter)

	process := &Process{
		cmd:        cmd,
		stdin:      stdin,
		lines:      make(chan LineResult),
		stderrTail: newTailBuffer(options.MaxStderrBytes),
		stderrDone: make(chan struct{}),
		waitDone:   make(chan struct{}),
		exitCode:   unknownExitCode,
		killGrace:  options.KillGrace,
	}

	go process.readStdout(stdoutReader, options)
	go process.readStderr(stderrReader)
	go process.waitExit()

	return process, nil
}

// classifyStartError 区分可执行文件不存在与其他启动失败
func classifyStartError(err error, options ProcessOptions) *core.Error {
	if errors.Is(err, exec.ErrNotFound) || errors.Is(err, os.ErrNotExist) {
		return core.WrapError(core.CategoryCLINotFound, "找不到可执行文件："+options.ExecutablePath, options.Provider, err)
	}

	return core.WrapError(core.CategoryCLIProcessFailed, "启动子进程失败："+options.ExecutablePath, options.Provider, err)
}

// closeFiles 关闭文件并忽略错误
func closeFiles(files ...*os.File) {
	for _, file := range files {
		_ = file.Close()
	}
}

// Lines 返回标准输出逐行结果，读到 EOF 后关闭。
func (p *Process) Lines() <-chan LineResult {
	return p.lines
}

// WriteLine 向标准输入写入一行。
func (p *Process) WriteLine(content []byte) error {
	p.stdinMutex.Lock()
	defer p.stdinMutex.Unlock()

	if p.isStdinClosed {
		return io.ErrClosedPipe
	}

	payload := make([]byte, 0, len(content)+1)
	payload = append(payload, content...)
	payload = append(payload, '\n')
	_, err := p.stdin.Write(payload)

	return err
}

// WriteAndCloseStdin 写入全部内容后关闭标准输入。
func (p *Process) WriteAndCloseStdin(content []byte) error {
	p.stdinMutex.Lock()
	defer p.stdinMutex.Unlock()

	if p.isStdinClosed {
		return io.ErrClosedPipe
	}

	_, writeErr := p.stdin.Write(content)
	p.isStdinClosed = true
	closeErr := p.stdin.Close()

	if writeErr != nil {
		return writeErr
	}

	return closeErr
}

// CloseStdin 关闭标准输入，通知子进程不再有输入。
func (p *Process) CloseStdin() {
	p.stdinMutex.Lock()
	defer p.stdinMutex.Unlock()

	if p.isStdinClosed {
		return
	}

	p.isStdinClosed = true
	_ = p.stdin.Close()
}

// Terminate 先向进程组发送 SIGTERM，等待 kill_grace 后仍未退出则发送 SIGKILL。
func (p *Process) Terminate() {
	p.terminateOnce.Do(func() {
		select {
		case <-p.waitDone:
			return
		default:
		}

		signalTerminate(p.cmd)

		go func() {
			timer := time.NewTimer(p.killGrace)
			defer timer.Stop()

			select {
			case <-p.waitDone:
			case <-timer.C:
				signalKill(p.cmd)
			}
		}()
	})
}

// Done 返回进程退出信号。
func (p *Process) Done() <-chan struct{} {
	return p.waitDone
}

// Wait 等待进程退出并返回退出码，被信号结束时为 -1。
func (p *Process) Wait() int {
	<-p.waitDone
	return p.exitCode
}

// WaitTimeout 等待进程退出，超时返回 false。
func (p *Process) WaitTimeout(timeout time.Duration) (int, bool) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case <-p.waitDone:
		return p.exitCode, true
	case <-timer.C:
		return unknownExitCode, false
	}
}

// StderrTail 返回 stderr 末尾内容，调用前应等待进程退出。
func (p *Process) StderrTail() string {
	timer := time.NewTimer(p.killGrace)
	defer timer.Stop()

	select {
	case <-p.stderrDone:
	case <-timer.C:
	}

	return p.stderrTail.String()
}

// waitExit 等待主进程退出，并结束进程组中残留的子孙进程，避免孤儿进程
func (p *Process) waitExit() {
	err := p.cmd.Wait()
	exitCode := unknownExitCode

	if p.cmd.ProcessState != nil {
		exitCode = p.cmd.ProcessState.ExitCode()
	}

	var exitErr *exec.ExitError
	if err != nil && !errors.As(err, &exitErr) {
		exitCode = unknownExitCode
	}

	p.exitCode = exitCode
	signalKillGroupAfterExit(p.cmd)
	close(p.waitDone)
}

// readStdout 按行读取标准输出，兼容半行，单行或累计输出超限时返回错误并停止读取
func (p *Process) readStdout(reader *os.File, options ProcessOptions) {
	defer close(p.lines)
	defer reader.Close()

	buffered := bufio.NewReaderSize(reader, 64*1024)
	var pending bytes.Buffer
	var totalBytes int64

	for {
		chunk, err := buffered.ReadSlice('\n')
		totalBytes += int64(len(chunk))

		if options.MaxOutputBytes > 0 && totalBytes > options.MaxOutputBytes {
			p.lines <- LineResult{Err: core.NewError(core.CategoryCLIProcessFailed, fmt.Sprintf("子进程标准输出超过上限 %d 字节", options.MaxOutputBytes), options.Provider)}
			return
		}

		pending.Write(chunk)

		if options.MaxLineBytes > 0 && int64(trimmedLength(pending.Bytes())) > options.MaxLineBytes {
			p.lines <- LineResult{Err: core.NewError(core.CategoryCLIProtocolError, fmt.Sprintf("单行输出超过上限 %d 字节", options.MaxLineBytes), options.Provider)}
			return
		}

		if errors.Is(err, bufio.ErrBufferFull) {
			continue
		}

		if err == nil {
			p.emitLine(pending.Bytes())
			pending.Reset()
			continue
		}

		if pending.Len() > 0 {
			p.emitLine(pending.Bytes())
		}

		return
	}
}

// trimmedLength 计算去掉行尾换行符后的长度
func trimmedLength(content []byte) int {
	return len(bytes.TrimRight(content, "\r\n"))
}

// emitLine 发送一行内容，空行跳过
func (p *Process) emitLine(content []byte) {
	trimmed := bytes.TrimRight(content, "\r\n")

	if len(bytes.TrimSpace(trimmed)) == 0 {
		return
	}

	copied := make([]byte, len(trimmed))
	copy(copied, trimmed)
	p.lines <- LineResult{Line: copied}
}

// readStderr 读取 stderr，只保留末尾若干字节
func (p *Process) readStderr(reader *os.File) {
	defer close(p.stderrDone)
	defer reader.Close()

	_, _ = io.Copy(p.stderrTail, reader)
}

// tailBuffer 只保留最后 limit 字节的写入缓冲区
type tailBuffer struct {
	mutex   sync.Mutex
	limit   int64
	content []byte
}

// newTailBuffer 创建末尾缓冲区
func newTailBuffer(limit int64) *tailBuffer {
	return &tailBuffer{limit: limit}
}

// Write 追加内容并丢弃超出上限的头部
func (b *tailBuffer) Write(content []byte) (int, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()

	if b.limit <= 0 {
		return len(content), nil
	}

	b.content = append(b.content, content...)

	if int64(len(b.content)) > b.limit {
		b.content = b.content[int64(len(b.content))-b.limit:]
	}

	return len(content), nil
}

// String 返回当前保留的内容
func (b *tailBuffer) String() string {
	b.mutex.Lock()
	defer b.mutex.Unlock()

	return string(b.content)
}
