package io.github.charleshyf.commonllm.examples;

import io.github.charleshyf.commonllm.CommonLlm;
import io.github.charleshyf.commonllm.CommonLlmOptions;
import io.github.charleshyf.commonllm.config.ConfigProvider;
import io.github.charleshyf.commonllm.config.ProviderConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 数据库配置示例：把配置表的行映射为 ConfigProvider，演示用内存行代替真实数据库
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class DatabaseConfigProviderExample {

	private DatabaseConfigProviderExample() {
	}

	public static void main(String[] arguments) {
		List<ConfigRow> rows = new ArrayList<>();
		rows.add(new ConfigRow("providers.deepseek", "type", "openai"));
		rows.add(new ConfigRow("providers.deepseek", "api", "chat_completions"));
		rows.add(new ConfigRow("providers.deepseek", "enabled", "true"));
		rows.add(new ConfigRow("providers.deepseek", "api_key", "secret-ref-resolved-by-caller"));
		rows.add(new ConfigRow("providers.deepseek", "base_url", "https://api.deepseek.example/v1"));
		rows.add(new ConfigRow("providers.deepseek", "default_model", "deepseek-chat"));

		try (CommonLlm commonLlm = CommonLlm.create(new CommonLlmOptions().configProvider(new TableConfigProvider(rows)))) {
			ProviderConfig deepseek = commonLlm.getConfig().getProviders().get("deepseek");
			System.out.println("已从配置表加载：" + deepseek);
			// 配置表变更后调用 reload，校验失败时保留旧配置
			commonLlm.reload();
		}
	}

	/**
	 * 配置表中的一行：scope 为点分路径，field 为字段名
	 */
	static final class ConfigRow {

		private final String scope;
		private final String field;
		private final String value;

		ConfigRow(String scope, String field, String value) {
			this.scope = scope;
			this.field = field;
			this.value = value;
		}
	}

	/**
	 * 把配置行组装为与配置文件相同结构的配置树
	 */
	static final class TableConfigProvider implements ConfigProvider {

		private final List<ConfigRow> rows;

		TableConfigProvider(List<ConfigRow> rows) {
			this.rows = rows;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Map<String, Object> load() {
			Map<String, Object> tree = new LinkedHashMap<>();

			for (ConfigRow row : rows) {
				Map<String, Object> current = tree;

				for (String segment : row.scope.split("\\.")) {
					current = (Map<String, Object>) current.computeIfAbsent(segment, key -> new LinkedHashMap<String, Object>());
				}

				current.put(row.field, row.value);
			}

			return tree;
		}
	}

	/**
	 * 从 JDBC 数据源读取配置行，表结构为 llm_config(scope, field, value)
	 */
	static List<ConfigRow> readRows(DataSource dataSource) throws SQLException {
		List<ConfigRow> rows = new ArrayList<>();

		try (Connection connection = dataSource.getConnection();
			PreparedStatement statement = connection.prepareStatement("SELECT scope, field, value FROM llm_config");
			ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				rows.add(new ConfigRow(resultSet.getString("scope"), resultSet.getString("field"), resultSet.getString("value")));
			}
		}

		return rows;
	}
}
