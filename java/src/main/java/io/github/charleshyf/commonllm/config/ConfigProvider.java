package io.github.charleshyf.commonllm.config;

import java.util.Map;

/**
 * 可注入的配置来源，例如数据库配置
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public interface ConfigProvider {

	/**
	 * 返回与配置文件结构相同的部分配置树，未提供的字段不出现
	 */
	Map<String, Object> load();
}
