package com.molagpt.app.feature.agentcontrol

/** A real model choice, plus the CLI default sentinel. Sourced from the live
 *  catalog the desktop discovers ([com.molagpt.app.core.model.AgentModelInfo]);
 *  falls back to model ids observed on relay metadata when the catalog is absent. */
data class AgentModelOption(
    val id: String?,
    val label: String,
    val description: String? = null,
)

const val AgentDefaultModelLabel = "默认（CLI 配置）"
