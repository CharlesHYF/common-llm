// Agent 运行时公共能力：可执行文件解析、版本探测、环境变量组装、工作目录校验与并发限制
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package agentruntime

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// probeOutputLimitBytes 探测命令输出的最大读取字节数
const probeOutputLimitBytes = 1024 * 1024

// semanticVersionParts 语义化版本的段数
const semanticVersionParts = 3

// baseEnvironmentNames 子进程运行必需、默认继承的环境变量
var baseEnvironmentNames = []string{
	"PATH",
	"HOME",
	"USER",
	"LOGNAME",
	"SHELL",
	"TMPDIR",
	"TMP",
	"TEMP",
	"LANG",
	"LC_ALL",
	"LC_CTYPE",
	"TERM",
	"SYSTEMROOT",
	"SYSTEMDRIVE",
	"WINDIR",
	"COMSPEC",
	"PATHEXT",
	"USERPROFILE",
	"APPDATA",
	"LOCALAPPDATA",
	"PROGRAMDATA",
}

// versionPattern 从版本输出中提取第一个 x.y.z
var versionPattern = regexp.MustCompile(`(\d+)\.(\d+)\.(\d+)`)

// ResolveExecutable 解析可执行文件路径，找不到时返回 CLI_NOT_FOUND。
func ResolveExecutable(executablePath string, provider string) (string, error) {
	resolved, err := exec.LookPath(executablePath)
	if err != nil {
		return "", core.WrapError(core.CategoryCLINotFound, "找不到可执行文件："+executablePath, provider, err)
	}

	absolute, err := filepath.Abs(resolved)
	if err != nil {
		return resolved, nil
	}

	return absolute, nil
}

// RunProbe 执行探测命令并返回标准输出与标准错误合并后的文本。
func RunProbe(ctx context.Context, executablePath string, args []string, env []string, provider string) (string, int, error) {
	probeContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()

	process, err := StartProcess(ProcessOptions{
		ExecutablePath: executablePath,
		Args:           args,
		Env:            env,
		KillGrace:      2 * time.Second,
		MaxLineBytes:   probeOutputLimitBytes,
		MaxOutputBytes: probeOutputLimitBytes,
		MaxStderrBytes: probeOutputLimitBytes,
		Provider:       provider,
	})
	if err != nil {
		return "", 0, err
	}

	process.CloseStdin()

	var builder strings.Builder
	for {
		select {
		case result, isOpen := <-process.Lines():
			if !isOpen {
				exitCode := process.Wait()
				builder.WriteString(process.StderrTail())
				return builder.String(), exitCode, nil
			}

			if result.Err != nil {
				process.Terminate()
				DrainLines(process.Lines())
				return "", 0, result.Err
			}

			builder.Write(result.Line)
			builder.WriteString("\n")
		case <-probeContext.Done():
			process.Terminate()
			DrainLines(process.Lines())
			return "", 0, core.WrapError(core.CategoryTimeout, "执行探测命令超时："+strings.Join(args, " "), provider, probeContext.Err())
		}
	}
}

// DrainLines 在后台读完剩余输出，避免读取协程阻塞。
func DrainLines(lines <-chan LineResult) {
	go func() {
		for range lines {
			continue
		}
	}()
}

// ParseVersion 从版本输出中提取 x.y.z，提取不到时返回空字符串。
func ParseVersion(output string) string {
	return versionPattern.FindString(output)
}

// CompareVersions 比较两个 x.y.z 版本，返回 -1、0、1。
func CompareVersions(left string, right string) int {
	leftParts := splitVersion(left)
	rightParts := splitVersion(right)

	for index := 0; index < semanticVersionParts; index++ {
		if leftParts[index] < rightParts[index] {
			return -1
		}

		if leftParts[index] > rightParts[index] {
			return 1
		}
	}

	return 0
}

// splitVersion 把版本拆成三段整数，非法段按 0 处理
func splitVersion(version string) [semanticVersionParts]int {
	var parts [semanticVersionParts]int
	matched := versionPattern.FindStringSubmatch(version)

	if len(matched) != semanticVersionParts+1 {
		return parts
	}

	for index := 0; index < semanticVersionParts; index++ {
		value, err := strconv.Atoi(matched[index+1])
		if err == nil {
			parts[index] = value
		}
	}

	return parts
}

// BuildEnvironment 组装子进程环境变量：运行必需变量、允许列表中的变量，再叠加认证变量。
func BuildEnvironment(allowlists [][]string, extra map[string]string) []string {
	allowed := map[string]bool{}

	for _, name := range baseEnvironmentNames {
		allowed[normalizeEnvName(name)] = true
	}

	for _, allowlist := range allowlists {
		for _, name := range allowlist {
			allowed[normalizeEnvName(name)] = true
		}
	}

	environment := []string{}
	for _, entry := range os.Environ() {
		name, _, hasSeparator := strings.Cut(entry, "=")
		if !hasSeparator {
			continue
		}

		if !allowed[normalizeEnvName(name)] {
			continue
		}

		if _, overridden := extra[name]; overridden {
			continue
		}

		environment = append(environment, entry)
	}

	for name, value := range extra {
		environment = append(environment, name+"="+value)
	}

	return environment
}

// normalizeEnvName Windows 环境变量名不区分大小写
func normalizeEnvName(name string) string {
	if runtime.GOOS == "windows" {
		return strings.ToUpper(name)
	}

	return name
}

// ResolveWorkingDirectory 校验工作目录存在且是目录，返回解析符号链接后的绝对路径。
func ResolveWorkingDirectory(directory string, provider string) (string, error) {
	if strings.TrimSpace(directory) == "" {
		return "", core.NewError(core.CategoryConfigInvalid, "未指定工作目录：请求 workingDirectory 与配置 working_directory 均为空", provider)
	}

	absolute, err := filepath.Abs(directory)
	if err != nil {
		return "", core.WrapError(core.CategoryConfigInvalid, "工作目录无法解析："+directory, provider, err)
	}

	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", core.WrapError(core.CategoryConfigInvalid, "工作目录不存在："+directory, provider, err)
		}

		return "", core.WrapError(core.CategoryConfigInvalid, "工作目录无法解析："+directory, provider, err)
	}

	info, err := os.Stat(resolved)
	if err != nil {
		return "", core.WrapError(core.CategoryConfigInvalid, "工作目录无法访问："+directory, provider, err)
	}

	if !info.IsDir() {
		return "", core.NewError(core.CategoryConfigInvalid, "工作目录不是目录："+directory, provider)
	}

	return resolved, nil
}

// RunLimiter 限制单个 AgentClient 同时运行的 run 数。
type RunLimiter struct {
	slots chan struct{}
}

// NewRunLimiter 创建并发限制器。
func NewRunLimiter(maxConcurrentRuns int) *RunLimiter {
	return &RunLimiter{slots: make(chan struct{}, maxConcurrentRuns)}
}

// TryAcquire 尝试占用一个名额，超出上限时返回 RATE_LIMITED。
func (l *RunLimiter) TryAcquire(provider string) error {
	select {
	case l.slots <- struct{}{}:
		return nil
	default:
		return core.NewError(core.CategoryRateLimited, fmt.Sprintf("同时运行的 run 数已达上限 %d", cap(l.slots)), provider)
	}
}

// Release 释放一个名额。
func (l *RunLimiter) Release() {
	<-l.slots
}

// RunRegistry 记录进行中的 run，用于 interrupt 与 close。
type RunRegistry struct {
	mutex sync.Mutex
	runs  map[string]func()
}

// NewRunRegistry 创建 run 注册表。
func NewRunRegistry() *RunRegistry {
	return &RunRegistry{runs: map[string]func(){}}
}

// Register 记录 run 的中断函数。
func (r *RunRegistry) Register(runID string, interrupt func()) {
	r.mutex.Lock()
	defer r.mutex.Unlock()

	r.runs[runID] = interrupt
}

// Unregister 删除 run 记录。
func (r *RunRegistry) Unregister(runID string) {
	r.mutex.Lock()
	defer r.mutex.Unlock()

	delete(r.runs, runID)
}

// Interrupt 调用指定 run 的中断函数，run 不存在时返回 false。
func (r *RunRegistry) Interrupt(runID string) bool {
	r.mutex.Lock()
	interrupt, exists := r.runs[runID]
	r.mutex.Unlock()

	if !exists {
		return false
	}

	interrupt()
	return true
}

// InterruptAll 中断全部进行中的 run。
func (r *RunRegistry) InterruptAll() {
	r.mutex.Lock()
	interrupts := make([]func(), 0, len(r.runs))
	for _, interrupt := range r.runs {
		interrupts = append(interrupts, interrupt)
	}
	r.mutex.Unlock()

	for _, interrupt := range interrupts {
		interrupt()
	}
}

// SecondsToDuration 把秒数转换为 time.Duration。
func SecondsToDuration(seconds float64) time.Duration {
	return time.Duration(seconds * float64(time.Second))
}
