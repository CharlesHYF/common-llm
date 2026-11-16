// 配置文档的读取、环境变量引用替换与按字段合并
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"go.yaml.in/yaml/v3"

	"github.com/CharlesHYF/common-llm/golang/core"
)

// Document 一个配置来源提供的部分字段，键名与配置文件一致（snake_case），未提供的字段不出现。
type Document map[string]any

// 环境变量引用语法标记
const (
	envReferencePrefix  = "${"
	envReferenceSuffix  = "}"
	envDefaultSeparator = ":-"
)

// LookupEnvFunc 读取环境变量，签名与 os.LookupEnv 一致。
type LookupEnvFunc func(name string) (string, bool)

// missingVariables 记录替换时缺失的环境变量，键为字段路径
type missingVariables map[string][]string

// SetPath 按点分路径写入值，中间层不存在时自动创建，例如 providers.openai.api_key。
func (d Document) SetPath(path string, value any) {
	segments := strings.Split(path, ".")
	current := map[string]any(d)

	for index, segment := range segments {
		if index == len(segments)-1 {
			current[segment] = value
			return
		}

		next, isMap := current[segment].(map[string]any)
		if !isMap {
			next = map[string]any{}
			current[segment] = next
		}

		current = next
	}
}

// readDocumentFile 读取 YAML 或 JSON 文件并替换环境变量引用
func readDocumentFile(path string, lookupEnv LookupEnvFunc) (Document, missingVariables, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, nil, core.WrapError(core.CategoryConfigInvalid, "读取配置文件失败："+path, "", err)
	}

	parsed := map[string]any{}
	extension := strings.ToLower(filepath.Ext(path))

	switch extension {
	case ".yaml", ".yml":
		err = yaml.Unmarshal(content, &parsed)
	case ".json":
		err = json.Unmarshal(content, &parsed)
	default:
		return nil, nil, core.NewError(core.CategoryConfigInvalid, "配置文件扩展名必须是 .yaml、.yml 或 .json："+path, "")
	}

	if err != nil {
		return nil, nil, core.WrapError(core.CategoryConfigInvalid, "解析配置文件失败："+path, "", err)
	}

	if parsed == nil {
		parsed = map[string]any{}
	}

	missing := missingVariables{}
	substituted := substituteValue(parsed, "", lookupEnv, missing)
	document, isMap := substituted.(map[string]any)

	if !isMap {
		return nil, nil, core.NewError(core.CategoryConfigInvalid, "配置文件顶层必须是对象："+path, "")
	}

	return Document(document), missing, nil
}

// substituteValue 递归替换字符串值中的环境变量引用，键名不替换
func substituteValue(value any, path string, lookupEnv LookupEnvFunc, missing missingVariables) any {
	switch typed := value.(type) {
	case string:
		replaced, missingNames := SubstituteEnv(typed, lookupEnv)

		if len(missingNames) > 0 {
			missing[path] = missingNames
		}

		return replaced
	case map[string]any:
		result := map[string]any{}

		for key, child := range typed {
			result[key] = substituteValue(child, joinPath(path, key), lookupEnv, missing)
		}

		return result
	case []any:
		result := make([]any, 0, len(typed))

		for index, child := range typed {
			result = append(result, substituteValue(child, fmt.Sprintf("%s[%d]", path, index), lookupEnv, missing))
		}

		return result
	default:
		return value
	}
}

// joinPath 拼接点分字段路径
func joinPath(parent string, key string) string {
	if parent == "" {
		return key
	}

	return parent + "." + key
}

// SubstituteEnv 替换 ${NAME} 与 ${NAME:-default}，$${NAME} 输出字面量 ${NAME}，返回缺失且无默认值的变量名。
func SubstituteEnv(text string, lookupEnv LookupEnvFunc) (string, []string) {
	if lookupEnv == nil {
		lookupEnv = os.LookupEnv
	}

	var builder strings.Builder
	missingNames := []string{}
	remaining := text

	for remaining != "" {
		if strings.HasPrefix(remaining, "$${") {
			closeIndex := strings.Index(remaining, envReferenceSuffix)

			if closeIndex < 0 {
				builder.WriteString(remaining)
				break
			}

			builder.WriteString(remaining[1 : closeIndex+1])
			remaining = remaining[closeIndex+1:]
			continue
		}

		if strings.HasPrefix(remaining, envReferencePrefix) {
			closeIndex := strings.Index(remaining, envReferenceSuffix)

			if closeIndex < 0 {
				builder.WriteString(remaining)
				break
			}

			reference := remaining[len(envReferencePrefix):closeIndex]
			resolved, isMissing := resolveEnvReference(reference, lookupEnv)

			if isMissing {
				missingNames = append(missingNames, reference)
			}

			builder.WriteString(resolved)
			remaining = remaining[closeIndex+1:]
			continue
		}

		builder.WriteByte(remaining[0])
		remaining = remaining[1:]
	}

	return builder.String(), missingNames
}

// resolveEnvReference 解析单个引用，返回替换值与是否缺失
func resolveEnvReference(reference string, lookupEnv LookupEnvFunc) (string, bool) {
	name := reference
	defaultValue := ""
	hasDefault := false
	separatorIndex := strings.Index(reference, envDefaultSeparator)

	if separatorIndex >= 0 {
		name = reference[:separatorIndex]
		defaultValue = reference[separatorIndex+len(envDefaultSeparator):]
		hasDefault = true
	}

	value, exists := lookupEnv(name)
	if exists && (value != "" || !hasDefault) {
		return value, false
	}

	if hasDefault {
		return defaultValue, false
	}

	return "", true
}

// mergeDocuments 按字段合并，后面的来源覆盖前面的同名字段，对象递归合并，数组与标量整体替换
func mergeDocuments(documents ...Document) Document {
	merged := map[string]any{}

	for _, document := range documents {
		mergeInto(merged, document)
	}

	return Document(merged)
}

// mergeInto 把 source 的字段合并进 target
func mergeInto(target map[string]any, source map[string]any) {
	for key, value := range source {
		sourceMap, isSourceMap := asStringMap(value)
		targetMap, isTargetMap := asStringMap(target[key])

		if isSourceMap && isTargetMap {
			mergeInto(targetMap, sourceMap)
			target[key] = targetMap
			continue
		}

		if isSourceMap {
			copied := map[string]any{}
			mergeInto(copied, sourceMap)
			target[key] = copied
			continue
		}

		target[key] = value
	}
}

// asStringMap 把 Document 与 map[string]any 统一为 map[string]any
func asStringMap(value any) (map[string]any, bool) {
	switch typed := value.(type) {
	case map[string]any:
		return typed, true
	case Document:
		return map[string]any(typed), true
	default:
		return nil, false
	}
}
