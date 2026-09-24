package com.molagpt.app.core.markdown.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// 内嵌组件的 props，在这里校验完，视图只会拿到合法的规格。报错写给看折叠源码的读者，
// 用中文并点名出错的字段。移植自桌面端 VisualSpecs / LayoutSpecs，两端规则一致。

/** 五种内嵌组件的规格。实例按源码缓存复用，相等性按引用即可。 */
sealed interface VisualSpec

data class Span(val min: Double, val max: Double)

data class PlotParam(val name: String, val min: Double, val max: Double, val default: Double, val step: Double)

enum class CurveKind {
    /** y = f(x) */
    EXPLICIT_Y,
    /** x = g(y) */
    EXPLICIT_X,
    /** F(x, y) = 0，按零点集描出。 */
    IMPLICIT,
    /** r = f(θ) */
    POLAR,
    /** (x(t), y(t)) */
    PARAMETRIC,
}

class PlotCurve(
    val kind: CurveKind,
    val source: String,
    val label: String? = null,
    val latex: String? = null,
    val error: String? = null,
    /** 按 [FunctionPlotSpec.variableOrder] 取值的求值函数。 */
    val fn: MathFn? = null,
    /** 参数曲线的 y 分量。 */
    val fnY: MathFn? = null,
    val tMin: Double = 0.0,
    val tMax: Double = 2 * PI,
)

class FunctionPlotSpec(
    val title: String?,
    val curves: List<PlotCurve>,
    val params: List<PlotParam>,
    val x: Span?,
    val y: Span?,
) : VisualSpec {
    companion object {
        const val MAX_CURVES = 6
        const val MAX_PARAMS = 4

        /** 每条编译好的曲线读取的值槽：x、y，再是参数曲线自变量的几种写法，最后是滑块。 */
        val FIXED_VARIABLES = listOf("x", "y", "t", "theta", "θ")

        private val PARAM_NAME = Regex("""^[\p{L}_][\p{L}\p{Nd}_]{0,11}$""")
        private val FUNCTION_HEAD = Regex("""^[A-Za-z]\s*\(\s*x\s*\)$""")

        fun parse(props: JsonObject): SpecResult<FunctionPlotSpec> {
            val parameters = ArrayList<PlotParam>()
            val defaultSteps = HashSet<String>()
            (props["params"] as? JsonArray)?.let { list ->
                for (item in list) {
                    if (parameters.size >= MAX_PARAMS) break
                    val param = when (val result = parseParam(item)) {
                        is SpecResult.Ok -> result.spec
                        is SpecResult.Error -> return result
                    }
                    if (parameters.any { it.name == param.name }) return SpecResult.Error("参数 ${param.name} 重复")
                    parameters += param
                    if (VisualJson.number(item, "step")?.takeIf { it > 0 } == null) defaultSteps += param.name
                }
            }

            val x = VisualJson.range(props, "x")
            x.error?.let { return SpecResult.Error(it) }
            val y = VisualJson.range(props, "y")
            y.error?.let { return SpecResult.Error(it) }

            var functions = props["functions"] ?: props["curves"]
            if (functions is JsonPrimitive && functions !is JsonNull || functions is JsonObject) {
                functions = JsonArray(listOf(functions))
            }
            if (functions !is JsonArray || functions.isEmpty()) return SpecResult.Error("functions 至少需要一条曲线")

            val order = FIXED_VARIABLES + parameters.map { it.name }
            val counting = HashSet<String>()
            val curves = functions.take(MAX_CURVES).map { parseCurve(it, order, counting) }
            // 用作项数的滑块，没写步长时按整数走：0.1 的步长只会让读数和实际项数对不上。
            val params = parameters.map { p ->
                if (p.name in counting && p.name in defaultSteps && p.min == floor(p.min) && p.max == floor(p.max)) {
                    p.copy(step = 1.0, default = floor(p.default + 0.5))
                } else {
                    p
                }
            }
            return SpecResult.Ok(FunctionPlotSpec(VisualJson.string(props, "title"), curves, params, x.span, y.span))
        }

        private fun parseParam(item: JsonElement): SpecResult<PlotParam> {
            val name = VisualJson.string(item, "name")?.trim()
            if (name.isNullOrEmpty() || !PARAM_NAME.matches(name)) return SpecResult.Error("params[].name 必须是简短的变量名")
            if (name in FIXED_VARIABLES || name == "e" || name == "pi") return SpecResult.Error("参数名 $name 与保留名冲突")
            val min = VisualJson.number(item, "min")
            val max = VisualJson.number(item, "max")
            if (min == null || max == null || !(min < max)) return SpecResult.Error("参数 $name 需要 min < max")
            val value = (VisualJson.number(item, "default") ?: VisualJson.number(item, "value") ?: (min + max) / 2)
                .coerceIn(min, max)
            val step = VisualJson.number(item, "step")?.takeIf { it > 0 } ?: niceStep((max - min) / 100)
            return SpecResult.Ok(PlotParam(name, min, max, value, step))
        }

        private fun parseCurve(item: JsonElement, order: List<String>, counting: MutableSet<String>): PlotCurve {
            var label: String? = null
            var expr: String? = null
            var px: String? = null
            var py: String? = null
            var t: Span? = null
            when {
                item is JsonPrimitive && item.isString -> expr = item.content
                item is JsonObject -> {
                    label = VisualJson.string(item, "label")
                    expr = VisualJson.string(item, "expr") ?: VisualJson.string(item, "fn")
                    px = VisualJson.string(item, "x")
                    py = VisualJson.string(item, "y")
                    t = VisualJson.range(item, "t").span ?: VisualJson.range(item, "theta").span
                }
            }

            return try {
                if (!px.isNullOrBlank() && !py.isNullOrBlank()) {
                    val ex = parse(px, order, counting)
                    val ey = parse(py, order, counting)
                    PlotCurve(
                        kind = CurveKind.PARAMETRIC,
                        source = "($px, $py)",
                        label = label,
                        latex = "\\left(${ex.toLatex()},\\ ${ey.toLatex()}\\right)",
                        fn = ex.compile(order),
                        fnY = ey.compile(order),
                        tMin = t?.min ?: 0.0,
                        tMax = t?.max ?: (2 * PI),
                    )
                } else if (expr.isNullOrBlank()) {
                    broken(expr.orEmpty(), label, "缺少 expr")
                } else {
                    parseEquation(expr.trim(), label, t, order, counting)
                }
            } catch (ex: MathSyntaxException) {
                broken(expr ?: "($px, $py)", label, ex.message ?: "表达式有误")
            }
        }

        private fun parseEquation(
            text: String,
            label: String?,
            t: Span?,
            order: List<String>,
            counting: MutableSet<String>,
        ): PlotCurve {
            val normalized = text.replace("==", "=")
            val parts = splitTopLevel(normalized)
            if (parts.size > 2) return broken(text, label, "只能有一个等号")

            if (parts.size == 1) {
                val e = parse(normalized, order, counting)
                val vars = e.variables
                val usesY = "y" in vars
                val usesX = "x" in vars
                if (usesY && usesX) return implicit(text, label, e, parse("0", order, counting), order)
                if (usesY) return broken(text, label, "只含 y 时请写成 x = …")
                return explicit(CurveKind.EXPLICIT_Y, text, label, "y", e, order)
            }

            val lhs = parts[0].trim()
            val rhs = parts[1].trim()
            if (lhs.isEmpty() || rhs.isEmpty()) return broken(text, label, "等式不完整")

            if (lhs == "y" || FUNCTION_HEAD.matches(lhs)) {
                return explicit(CurveKind.EXPLICIT_Y, text, label, lhs, parse(rhs, order, counting), order)
            }
            if (lhs == "x") return explicit(CurveKind.EXPLICIT_X, text, label, "x", parse(rhs, order, counting), order)
            if (lhs == "r" || lhs == "ρ") {
                val polar = parse(rhs, order, counting)
                return PlotCurve(
                    kind = CurveKind.POLAR,
                    source = text,
                    label = label,
                    latex = "r = " + polar.toLatex(),
                    fn = polar.compile(order),
                    tMin = t?.min ?: 0.0,
                    tMax = t?.max ?: (2 * PI),
                )
            }
            return implicit(text, label, parse(lhs, order, counting), parse(rhs, order, counting), order)
        }

        /** 解析一条曲线里的表达式，顺带记下哪些滑块被用作求和上下限。 */
        private fun parse(text: String, order: List<String>, counting: MutableSet<String>): MathExpression =
            MathExpression.parse(text, order).also { counting += it.countingVariables }

        private fun explicit(
            kind: CurveKind,
            text: String,
            label: String?,
            lhs: String,
            e: MathExpression,
            order: List<String>,
        ): PlotCurve {
            val allowed = if (kind == CurveKind.EXPLICIT_Y) "x" else "y"
            val stray = e.variables.firstOrNull { it in FIXED_VARIABLES && it != allowed }
            if (stray != null) {
                return broken(
                    text,
                    label,
                    if (kind == CurveKind.EXPLICIT_Y) "y = … 右边只能用 x（出现了 $stray）" else "x = … 右边只能用 y（出现了 $stray）",
                )
            }
            val head = if (lhs.length > 1) lhs.replace(" ", "") else lhs
            return PlotCurve(kind = kind, source = text, label = label, latex = "$head = ${e.toLatex()}", fn = e.compile(order))
        }

        private fun implicit(
            text: String,
            label: String?,
            lhs: MathExpression,
            rhs: MathExpression,
            order: List<String>,
        ): PlotCurve {
            val fl = lhs.compile(order)
            val fr = rhs.compile(order)
            return PlotCurve(
                kind = CurveKind.IMPLICIT,
                source = text,
                label = label,
                latex = lhs.toLatex() + " = " + rhs.toLatex(),
                fn = MathFn { fl.eval(it) - fr.eval(it) },
            )
        }

        /** 只在括号外拆等号：sum(k=1, …) 里的等号属于求和。 */
        private fun splitTopLevel(text: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, c ->
                when (c) {
                    '(', '[', '（' -> depth++
                    ')', ']', '）' -> depth--
                    '=' -> if (depth == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
            parts += text.substring(start)
            return parts
        }

        private fun broken(text: String, label: String?, error: String) =
            PlotCurve(kind = CurveKind.EXPLICIT_Y, source = text, label = label, error = error)

        fun niceStep(raw: Double): Double {
            if (!(raw > 0) || raw.isInfinite()) return 0.01
            val exponent = floor(log10(raw))
            val fraction = raw / 10.0.pow(exponent)
            val nice = when {
                fraction <= 1 -> 1.0
                fraction <= 2 -> 2.0
                fraction <= 5 -> 5.0
                else -> 10.0
            }
            return nice * 10.0.pow(exponent)
        }
    }
}

enum class ChartType { LINE, BAR, AREA, SCATTER, PIE }

/** [values] 给折线 / 柱状 / 面积 / 饼图，[points] 给散点。 */
class ChartSeries(val name: String, val values: List<Double?>, val points: List<Pair<Double, Double>>)

class ChartSpec(
    val title: String?,
    val type: ChartType,
    val categories: List<String>,
    val series: List<ChartSeries>,
    val unit: String?,
    val stacked: Boolean,
) : VisualSpec {
    companion object {
        const val MAX_SERIES = 8
        const val MAX_POINTS = 400

        fun parse(props: JsonObject): SpecResult<ChartSpec> {
            val typeText = (VisualJson.string(props, "type") ?: "line").trim().lowercase()
            val type = when (typeText) {
                "line", "折线", "spline" -> ChartType.LINE
                "bar", "column", "柱状", "horizontal-bar" -> ChartType.BAR
                "area", "面积" -> ChartType.AREA
                "scatter", "point", "散点" -> ChartType.SCATTER
                "pie", "donut", "doughnut", "饼图" -> ChartType.PIE
                else -> return SpecResult.Error("type 不支持 $typeText，可用 line / bar / area / scatter / pie")
            }

            val categories = ArrayList<String>()
            for (key in listOf("x", "labels", "categories")) {
                val xs = props[key] as? JsonArray ?: continue
                for (item in xs) {
                    if (categories.size >= MAX_POINTS) break
                    categories += VisualJson.text(item)
                }
                break
            }

            val seriesNodes = ArrayList<JsonElement>()
            when (val node = props["series"]) {
                is JsonArray -> seriesNodes.addAll(node)
                is JsonObject -> seriesNodes.add(node)
                else -> if (props["data"] != null) seriesNodes.add(props)
            }

            val series = ArrayList<ChartSeries>()
            for (node in seriesNodes) {
                if (series.size >= MAX_SERIES) break
                val data = (node as? JsonObject)?.get("data") as? JsonArray
                    ?: return SpecResult.Error("series[].data 必须是数组")
                val name = VisualJson.string(node, "name") ?: "系列 ${series.size + 1}"
                val values = ArrayList<Double?>()
                val points = ArrayList<Pair<Double, Double>>()
                if (type == ChartType.SCATTER) {
                    readPoints(data, points)
                } else {
                    for (item in data) {
                        if (values.size >= MAX_POINTS) break
                        values += when {
                            VisualJson.isNumber(item) -> (item as JsonPrimitive).content.toDoubleOrNull()
                            item is JsonPrimitive && item.isString -> item.content.trim().toDoubleOrNull()
                            item is JsonObject -> VisualJson.number(item, "value")
                            else -> null
                        }
                    }
                }
                series += ChartSeries(name, values, points)
            }

            if (series.isEmpty() || series.all { s -> s.values.all { it == null } && s.points.isEmpty() }) {
                return SpecResult.Error("series 里没有可用的数据")
            }

            if (type != ChartType.SCATTER) {
                val length = series.maxOf { it.values.size }
                for (i in categories.size until length) categories += (i + 1).toString()
            }

            val stacked = (props["stacked"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
            return SpecResult.Ok(
                ChartSpec(VisualJson.string(props, "title"), type, categories, series, VisualJson.string(props, "unit"), stacked),
            )
        }

        private fun readPoints(data: JsonArray, points: MutableList<Pair<Double, Double>>) {
            if (data.isNotEmpty() && data.all { VisualJson.isNumber(it) }) {
                var i = 0
                while (i + 1 < data.size && points.size < MAX_POINTS) {
                    points += VisualJson.numberOf(data[i])!! to VisualJson.numberOf(data[i + 1])!!
                    i += 2
                }
                return
            }
            for (item in data) {
                if (points.size >= MAX_POINTS) break
                if (item is JsonArray && item.size >= 2 && VisualJson.isNumber(item[0]) && VisualJson.isNumber(item[1])) {
                    points += VisualJson.numberOf(item[0])!! to VisualJson.numberOf(item[1])!!
                } else if (item is JsonObject) {
                    val x = VisualJson.number(item, "x")
                    val y = VisualJson.number(item, "y")
                    if (x != null && y != null) points += x to y
                }
            }
        }
    }
}

sealed interface SpecResult<out T> {
    data class Ok<T>(val spec: T) : SpecResult<T>
    data class Error(val message: String) : SpecResult<Nothing>
}

internal data class RangeResult(val span: Span?, val error: String?)

internal object VisualJson {
    fun primitive(node: JsonElement?): JsonPrimitive? = (node as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun isNumber(node: JsonElement?): Boolean {
        val p = primitive(node) ?: return false
        return !p.isString && p.booleanOrNull == null && p.content.toDoubleOrNull() != null
    }

    fun isBoolean(node: JsonElement?): Boolean {
        val p = primitive(node) ?: return false
        return !p.isString && p.booleanOrNull != null
    }

    fun isBlank(node: JsonElement?): Boolean {
        if (node == null || node is JsonNull) return true
        return node is JsonPrimitive && node.isString && node.content.isBlank()
    }

    fun string(owner: JsonElement?, name: String): String? =
        primitive((owner as? JsonObject)?.get(name))?.takeIf { it.isString }?.content

    /** 字符串原样；数字按模型写的字面值；其他取 JSON 文本。 */
    fun text(node: JsonElement): String = when {
        node is JsonPrimitive && node !is JsonNull -> node.content
        else -> node.toString()
    }

    fun number(owner: JsonElement?, name: String): Double? = numberOf((owner as? JsonObject)?.get(name))

    fun numberOf(node: JsonElement?): Double? {
        val p = primitive(node) ?: return null
        if (!p.isString) return if (p.booleanOrNull != null) null else p.content.toDoubleOrNull()
        val text = p.content.trim()
        text.toDoubleOrNull()?.let { return it }
        // "2*pi"、"-pi"——范围常按读者理解的方式写。
        return try {
            val e = MathExpression.parse(text)
            if (e.variables.isEmpty()) e.compile(emptyList()).eval(DoubleArray(0)) else null
        } catch (_: MathSyntaxException) {
            null
        }
    }

    fun range(owner: JsonElement?, name: String): RangeResult {
        val node = (owner as? JsonObject)?.get(name)
        if (node == null || node is JsonNull) return RangeResult(null, null)
        if (node !is JsonArray || node.size != 2) return RangeResult(null, "$name 应写成 [最小值, 最大值]")
        val min = numberOf(node[0])
        val max = numberOf(node[1])
        if (min == null || max == null || !(min < max) || min.isInfinite() || max.isInfinite()) {
            return RangeResult(null, "$name 需要满足 最小值 < 最大值")
        }
        return RangeResult(Span(min, max), null)
    }
}
