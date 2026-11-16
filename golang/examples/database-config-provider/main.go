// 示例：把配置表映射为 ConfigProvider，提供 database/sql 实现与可直接运行的内存实现
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strconv"

	commonllm "github.com/CharlesHYF/common-llm/golang"
	"github.com/CharlesHYF/common-llm/golang/config"
)

// ConfigRow 配置表中的一行，path 为点分字段路径，例如 providers.openai.api_key
type ConfigRow struct {
	Path  string
	Value string
}

// ConfigRowSource 读取配置表的全部行
type ConfigRowSource interface {
	QueryConfigRows(ctx context.Context) ([]ConfigRow, error)
}

// SQLConfigRowSource 基于 database/sql 读取 common_llm_config 表，驱动由调用方注册
type SQLConfigRowSource struct {
	DB *sql.DB
}

// QueryConfigRows 查询 path 与 value 两列
func (s SQLConfigRowSource) QueryConfigRows(ctx context.Context) ([]ConfigRow, error) {
	rows, err := s.DB.QueryContext(ctx, "SELECT path, value FROM common_llm_config")
	if err != nil {
		return nil, fmt.Errorf("查询配置表失败: %w", err)
	}
	defer rows.Close()

	configRows := []ConfigRow{}
	for rows.Next() {
		var row ConfigRow
		if err := rows.Scan(&row.Path, &row.Value); err != nil {
			return nil, fmt.Errorf("读取配置行失败: %w", err)
		}

		configRows = append(configRows, row)
	}

	return configRows, rows.Err()
}

// MemoryConfigRowSource 内存中的配置表，用于演示与本地运行
type MemoryConfigRowSource struct {
	Rows []ConfigRow
}

// QueryConfigRows 返回内存中的全部行
func (s MemoryConfigRowSource) QueryConfigRows(ctx context.Context) ([]ConfigRow, error) {
	return s.Rows, nil
}

// DatabaseConfigProvider 把配置表的行转换为配置文档
type DatabaseConfigProvider struct {
	Source ConfigRowSource
}

// Load 读取全部行并按路径写入文档，布尔与数字字符串转换为对应类型
func (p DatabaseConfigProvider) Load(ctx context.Context) (config.Document, error) {
	rows, err := p.Source.QueryConfigRows(ctx)
	if err != nil {
		return nil, err
	}

	document := config.Document{}
	for _, row := range rows {
		document.SetPath(row.Path, parseValue(row.Value))
	}

	return document, nil
}

// parseValue 把文本值转换为布尔、整数、小数或保留字符串
func parseValue(value string) any {
	if parsed, err := strconv.ParseBool(value); err == nil {
		return parsed
	}

	if parsed, err := strconv.ParseInt(value, 10, 64); err == nil {
		return parsed
	}

	if parsed, err := strconv.ParseFloat(value, 64); err == nil {
		return parsed
	}

	return value
}

func main() {
	ctx := context.Background()
	provider := DatabaseConfigProvider{Source: MemoryConfigRowSource{Rows: []ConfigRow{
		{Path: "providers.gemini.enabled", Value: "true"},
		{Path: "providers.gemini.api_key", Value: os.Getenv("GEMINI_API_KEY")},
		{Path: "providers.gemini.default_model", Value: os.Getenv("GEMINI_MODEL")},
		{Path: "common.max_retries", Value: "1"},
	}}}

	client, err := commonllm.Create(ctx, commonllm.Options{ConfigProvider: provider})
	if err != nil {
		fmt.Fprintln(os.Stderr, "配置非法：", err)
		os.Exit(1)
	}
	defer client.Close()

	fmt.Println("当前配置：", client.Config().Providers["gemini"])

	// 配置表更新后调用 Reload，失败时继续使用旧配置
	if err := client.Reload(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "重新加载失败，继续使用旧配置：", err)
	}
}
