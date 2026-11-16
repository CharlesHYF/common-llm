// 按优先级加载各配置来源并合并为 CommonLlmConfig
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package config

import (
	"context"
	"errors"
	"os"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// ConfigProvider 注入的配置来源，例如数据库配置，返回部分字段。
type ConfigProvider interface {
	Load(ctx context.Context) (Document, error)
}

// ConfigProviderFunc 把函数适配为 ConfigProvider。
type ConfigProviderFunc func(ctx context.Context) (Document, error)

// Load 调用函数本身。
func (f ConfigProviderFunc) Load(ctx context.Context) (Document, error) {
	return f(ctx)
}

// FileConfigProvider 从 YAML 或 JSON 文件加载配置，并替换 ${ENV} 引用。
type FileConfigProvider struct {
	Path      string
	LookupEnv LookupEnvFunc
}

// Load 读取文件并返回替换环境变量后的配置文档。
func (p FileConfigProvider) Load(ctx context.Context) (Document, error) {
	document, _, err := readDocumentFile(p.Path, p.LookupEnv)
	return document, err
}

// Resolver 按 运行时覆盖 > ConfigProvider > 配置文件 > 默认值 的优先级生成配置。
type Resolver struct {
	// FilePath 显式指定的配置文件路径，优先于环境变量 COMMON_LLM_CONFIG 与默认文件
	FilePath string
	// Provider 注入的配置来源，可为空
	Provider ConfigProvider
	// Overrides 运行时显式覆盖，可为空
	Overrides Document
	// LookupEnv 读取环境变量，为空时使用 os.LookupEnv
	LookupEnv LookupEnvFunc
}

// Resolve 加载全部来源、合并并校验，任何一步失败都返回 *core.Error。
func (r *Resolver) Resolve(ctx context.Context) (*CommonLlmConfig, error) {
	lookupEnv := r.LookupEnv
	if lookupEnv == nil {
		lookupEnv = os.LookupEnv
	}

	fileDocument, missing, err := r.loadFileLayer(lookupEnv)
	if err != nil {
		return nil, err
	}

	var providerDocument Document
	if r.Provider != nil {
		providerDocument, err = r.Provider.Load(ctx)

		if err != nil {
			return nil, core.WrapError(core.CategoryConfigInvalid, "ConfigProvider 加载配置失败", "", err)
		}
	}

	if fileDocument == nil && r.Provider == nil && r.Overrides == nil {
		return nil, core.NewError(core.CategoryConfigInvalid, "未找到配置：未传入配置文件路径、未设置 COMMON_LLM_CONFIG，当前目录也没有 common-llm.yaml", "")
	}

	merged := mergeDocuments(fileDocument, providerDocument, r.Overrides)
	return buildConfig(merged, missing)
}

// loadFileLayer 按 显式路径 > COMMON_LLM_CONFIG > ./common-llm.yaml 的顺序定位配置文件
func (r *Resolver) loadFileLayer(lookupEnv LookupEnvFunc) (Document, missingVariables, error) {
	if r.FilePath != "" {
		return readDocumentFile(r.FilePath, lookupEnv)
	}

	environmentPath, exists := lookupEnv(ConfigPathEnvironmentVariable)
	if exists && environmentPath != "" {
		return readDocumentFile(environmentPath, lookupEnv)
	}

	_, statErr := os.Stat(DefaultConfigFileName)
	if errors.Is(statErr, os.ErrNotExist) {
		return nil, missingVariables{}, nil
	}

	return readDocumentFile(DefaultConfigFileName, lookupEnv)
}

// LoadFile 只从指定文件加载配置，适合不需要其他来源的场景。
func LoadFile(ctx context.Context, path string) (*CommonLlmConfig, error) {
	resolver := Resolver{FilePath: path}
	return resolver.Resolve(ctx)
}
