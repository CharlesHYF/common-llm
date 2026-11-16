// 示例：文生图并把生成的图片保存到文件
// 创建日期：2026-11-16
// 修改日期：2026-11-16

package main

import (
	"context"
	"fmt"
	"os"
	"strings"

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

	response, err := client.GenerateImage(ctx, &core.ImageRequest{
		Provider: "openai",
		Prompt:   "一只橘猫坐在窗台上，水彩风格",
		Size:     "1024x1024",
		Quality:  "low",
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, "图片生成失败：", err)
		os.Exit(1)
	}

	for index, image := range response.Images {
		fileName := fmt.Sprintf("image-%d.%s", index, strings.TrimPrefix(image.MimeType, "image/"))
		if err := os.WriteFile(fileName, image.Data, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, "保存图片失败：", err)
			os.Exit(1)
		}

		fmt.Println("已保存：", fileName)
	}
}
