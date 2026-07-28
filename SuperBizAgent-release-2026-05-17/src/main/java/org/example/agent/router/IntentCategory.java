package org.example.agent.router;

/**
 * 意图类别枚举
 * 用于 IntentRouter 对用户输入进行分类后的路由决策
 */
public enum IntentCategory {
    /** 告警排查 — 路由到 AiOpsService (SupervisorAgent) */
    ALERT_DIAGNOSIS,
    /** 知识检索 — 路由到 ReactAgent + queryInternalDocs */
    KNOWLEDGE_RETRIEVAL,
    /** 通用对话 — 路由到标准 ReactAgent（无 RAG 工具） */
    GENERAL_CHAT,
    /** 意图不明确 — 路由到通用 Chat + 引导追问 */
    UNCLEAR
}
