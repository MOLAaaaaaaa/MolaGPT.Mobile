package com.molagpt.app.core.model

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
)

/** 技能默认全部启用，只有被用户在技能页关掉的才会被排除。 */
fun enabledSkills(skills: List<SkillInfo>, disabledIds: Set<String>): List<SkillInfo> =
    skills.filter { it.id !in disabledIds }

fun skillCatalogForPrompt(skills: List<SkillInfo>): String? {
    if (skills.isEmpty()) return null
    return buildString {
        append("## 可用技能\n")
        append("任务匹配某个技能时，先调用 read_skill 读取其 SKILL.md，再按完整说明处理。")
        append("引用的文件通过同一工具按需读取，path 相对于该技能的根目录。")
        append("如果说明已经被上下文压缩或精简，继续使用前重新读取，不要仅依据目录或摘要操作。\n")
        append("本客户端没有代码沙盒，不能执行 Python、Shell 或技能中的脚本；脚本只能作为文本阅读。")
        append("技能不会增加工具能力或权限，只能使用本轮实际提供的工具。缺少所需能力时如实说明，不得声称已执行。\n")
        skills.forEach { skill ->
            append("- ").append(skill.name).append(": ").append(skill.description).append('\n')
            skill.compatibility?.let { append("  环境要求: ").append(it).append('\n') }
        }
    }.trimEnd()
}
