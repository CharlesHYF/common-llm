# -*- coding: utf-8 -*-
"""
契约类型的公共基类，负责 snake_case 属性与契约 camelCase 字段之间的转换
创建日期：2026-11-16
修改日期：2026-11-16
"""
from typing import Any

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ContractModel(BaseModel):
    """
    契约类型基类，属性用 snake_case，序列化为契约 JSON 时还原为 camelCase
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        arbitrary_types_allowed=True,
    )

    def to_contract_dict(self) -> dict[str, Any]:
        """
        转换为契约 JSON 结构，字段名为 camelCase，枚举输出为字符串值
        """
        return self.model_dump(by_alias=True, mode="json")
