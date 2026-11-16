package io.github.charleshyf.commonllm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型请求中的一条消息
 * 创建日期：2026-11-16
 * 修改日期：2026-11-16
 */
public final class Message {

	private final MessageRole role;
	private final List<ContentPart> contentParts;

	public Message(MessageRole role, List<ContentPart> contentParts) {
		this.role = role;
		this.contentParts = Collections.unmodifiableList(new ArrayList<>(contentParts));
	}

	public static Message system(String text) {
		return new Message(MessageRole.SYSTEM, List.of(ContentPart.text(text)));
	}

	public static Message user(String text) {
		return new Message(MessageRole.USER, List.of(ContentPart.text(text)));
	}

	public static Message assistant(String text) {
		return new Message(MessageRole.ASSISTANT, List.of(ContentPart.text(text)));
	}

	public MessageRole getRole() {
		return role;
	}

	public List<ContentPart> getContentParts() {
		return contentParts;
	}

	/**
	 * 拼接该消息内全部 text 片段
	 */
	public String joinText() {
		StringBuilder textBuilder = new StringBuilder();

		for (ContentPart contentPart : contentParts) {
			if (contentPart.getType() == ContentPartType.TEXT && contentPart.getText() != null) {
				textBuilder.append(contentPart.getText());
			}
		}

		return textBuilder.toString();
	}
}
