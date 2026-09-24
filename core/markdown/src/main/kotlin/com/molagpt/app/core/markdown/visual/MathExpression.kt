package com.molagpt.app.core.markdown.visual

import java.util.Locale
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

class MathSyntaxException(message: String) : Exception(message)

/** 编译后的表达式：按 [MathExpression.compile] 给定的变量顺序读取值数组。 */
fun interface MathFn {
    fun eval(values: DoubleArray): Double
}

/**
 * 函数图像用的小型封闭数学语言：数字、变量、+ - * / ^、固定的函数表和 pi / e。
 * 模型写在这里的任何东西只能调用到 [kotlin.math]——表达式先解析成自己的语法树，
 * 再从树编译成闭包，从不当作代码执行。移植自桌面端 MathExpression，两端行为保持一致。
 *
 * 对数学写法宽容：隐式乘法（2x、3(x+1)、(x+1)(x-1)）、`**` 表示乘方、Unicode 运算符、
 * `sin^2(x)`、`|x|`。该严的地方严：函数必须带括号。
 *
 * 求和 `sum(k=1, n, 表达式)` 与连乘 `prod(…)` 是唯一带约束变量的写法：项数随滑块变化的
 * 式子（级数逼近）没有别的写法能表达。上下限向下取整，最多 [MAX_TERMS] 项，不能嵌套——
 * 保证拖滑块时每个采样点的计算量有上界。
 */
class MathExpression private constructor(internal val root: Node, val source: String) {

    /** 自由变量名，按首次出现的顺序。求和变量不算。 */
    val variables: List<String>
        get() = ArrayList<String>().also { collect(root, it, emptySet()) }

    /** 出现在求和、连乘上下限里的自由变量：这类滑块只取整数才有意义。 */
    val countingVariables: Set<String>
        get() = HashSet<String>().also { collectCounting(root, it) }

    /** 编译成按 [variableOrder] 排列的值数组求值的函数。用到列表外的名字时抛出。 */
    fun compile(variableOrder: List<String>): MathFn = emit(root, variableOrder)

    fun toLatex(): String = latex(root, 0)

    // ---- tree ------------------------------------------------------------

    internal sealed interface Node
    internal data class Num(val value: Double) : Node
    internal data class Var(val name: String) : Node
    internal data class Const(val name: String, val value: Double) : Node
    internal data class Neg(val operand: Node) : Node
    internal data class Bin(val op: Char, val left: Node, val right: Node, val implicit: Boolean = false) : Node
    internal data class Call(val name: String, val args: List<Node>) : Node
    /** 求和（[product] 为 false）或连乘：[index] 从 [from] 到 [to] 逐个取整数代入 [body]。 */
    internal data class Iter(val product: Boolean, val index: String, val from: Node, val to: Node, val body: Node) : Node

    companion object {
        const val MAX_TERMS = 1000

        /** 上下限取整前的容差：滑块按小数步长吸附时，3 可能落成 2.9999999999999996。 */
        private const val BOUND_EPSILON = 1e-9

        /**
         * @param knownNames 允许出现的名字（绘图变量和滑块参数）。只用来拆开连写的乘积，
         *   例如 `ax`、`sinx`。
         */
        fun parse(text: String, knownNames: Collection<String> = emptyList()): MathExpression {
            val parser = Parser(normalize(text), knownNames)
            return MathExpression(parser.parseAll(), text)
        }

        fun formatNumber(value: Double): String {
            if (value.isNaN()) return "NaN"
            val a = abs(value)
            if (a >= 1e6 || (a < 1e-4 && value != 0.0)) return String.format(Locale.ROOT, "%.3e", value)
                .replace(Regex("""\.?0+e"""), "e").replace("e+0", "e+").replace("e-0", "e-")
            return trimNumber(String.format(Locale.ROOT, "%.4f", value))
        }

        private fun trimNumber(text: String): String =
            if ('.' in text) text.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it } else text

        private fun collect(node: Node, names: MutableList<String>, bound: Set<String>) {
            when (node) {
                is Var -> if (node.name !in bound && node.name !in names) names += node.name
                is Neg -> collect(node.operand, names, bound)
                is Bin -> { collect(node.left, names, bound); collect(node.right, names, bound) }
                is Call -> node.args.forEach { collect(it, names, bound) }
                is Iter -> {
                    collect(node.from, names, bound)
                    collect(node.to, names, bound)
                    collect(node.body, names, bound + node.index)
                }
                else -> Unit
            }
        }

        private fun collectCounting(node: Node, names: MutableSet<String>) {
            when (node) {
                is Neg -> collectCounting(node.operand, names)
                is Bin -> { collectCounting(node.left, names); collectCounting(node.right, names) }
                is Call -> node.args.forEach { collectCounting(it, names) }
                is Iter -> {
                    val bounds = ArrayList<String>()
                    collect(node.from, bounds, emptySet())
                    collect(node.to, bounds, emptySet())
                    names += bounds
                }
                else -> Unit
            }
        }

        // ---- functions ---------------------------------------------------------

        private val aliases = mapOf(
            "arcsin" to "asin", "arccos" to "acos", "arctan" to "atan",
            "log10" to "lg", "sgn" to "sign", "√" to "sqrt",
            "ceiling" to "ceil", "arsinh" to "asinh", "arcosh" to "acosh", "artanh" to "atanh",
            "factorial" to "fact", "product" to "prod",
        )

        /** 函数名 → (最少参数, 最多参数)。 */
        private val functions: Map<String, IntRange> = mapOf(
            "sin" to 1..1, "cos" to 1..1, "tan" to 1..1,
            "cot" to 1..1, "sec" to 1..1, "csc" to 1..1,
            "asin" to 1..1, "acos" to 1..1, "atan" to 1..2, "atan2" to 2..2,
            "sinh" to 1..1, "cosh" to 1..1, "tanh" to 1..1,
            "asinh" to 1..1, "acosh" to 1..1, "atanh" to 1..1,
            "sqrt" to 1..1, "cbrt" to 1..1, "abs" to 1..1, "exp" to 1..1,
            "ln" to 1..1, "log" to 1..2, "lg" to 1..1, "log2" to 1..1,
            "floor" to 1..1, "ceil" to 1..1, "round" to 1..1, "sign" to 1..1,
            "min" to 2..8, "max" to 2..8, "pow" to 2..2, "mod" to 2..2, "hypot" to 2..2,
            "fact" to 1..1, "sum" to 4..4, "prod" to 4..4,
        )

        private val iterators = setOf("sum", "prod")

        // 求和、连乘要带约束变量，不参与「sinx → sin(x)」式的连写拆分。
        private val functionsByLength = functions.keys.filter { it !in iterators }.sortedByDescending { it.length }

        private fun resolveFunction(name: String): String? {
            var lower = name.lowercase(Locale.ROOT)
            aliases[lower]?.let { lower = it }
            return lower.takeIf { it in functions }
        }

        private fun constant(name: String): Double? = when (name) {
            "pi", "π", "PI" -> PI
            "e" -> E
            "tau", "τ" -> 2 * PI
            else -> null
        }

        // ---- normalize ---------------------------------------------------------

        private fun normalize(text: String): String {
            val sb = StringBuilder(text.length + 8)
            for (c in text) {
                when (c) {
                    '−', '–', '—' -> sb.append('-')
                    '×', '·', '∙', '⋅' -> sb.append('*')
                    '÷' -> sb.append('/')
                    '（' -> sb.append('(')
                    '）' -> sb.append(')')
                    '，' -> sb.append(',')
                    '²' -> sb.append("^2")
                    '³' -> sb.append("^3")
                    'π' -> sb.append("(pi)")
                    '√' -> sb.append(" sqrt")
                    else -> sb.append(c)
                }
            }
            return sb.toString().replace("**", "^")
        }

        // ---- compile -----------------------------------------------------------

        private fun emit(node: Node, order: List<String>): MathFn = when (node) {
            is Num -> node.value.let { v -> MathFn { v } }
            is Const -> node.value.let { v -> MathFn { v } }
            is Var -> {
                // 取最后一个：求和变量追加在末尾，与滑块同名时以求和变量为准。
                val index = order.lastIndexOf(node.name)
                if (index < 0) throw MathSyntaxException("未知变量 ${node.name}")
                MathFn { it[index] }
            }
            is Neg -> emit(node.operand, order).let { f -> MathFn { -f.eval(it) } }
            is Bin -> {
                val l = emit(node.left, order)
                val r = emit(node.right, order)
                when (node.op) {
                    '+' -> MathFn { l.eval(it) + r.eval(it) }
                    '-' -> MathFn { l.eval(it) - r.eval(it) }
                    '*' -> MathFn { l.eval(it) * r.eval(it) }
                    '/' -> MathFn { l.eval(it) / r.eval(it) }
                    '^' -> MathFn { power(l.eval(it), r.eval(it)) }
                    else -> throw MathSyntaxException("未知运算 ${node.op}")
                }
            }
            is Call -> emitCall(node.name, node.args.map { emit(it, order) })
            is Iter -> emitIter(node, order)
        }

        private fun emitIter(node: Iter, order: List<String>): MathFn {
            val from = emit(node.from, order)
            val to = emit(node.to, order)
            val slot = order.size
            val body = emit(node.body, order + node.index)
            val product = node.product
            return MathFn { values ->
                val lo = floor(from.eval(values) + BOUND_EPSILON)
                val hi = floor(to.eval(values) + BOUND_EPSILON)
                // NaN 的比较恒为 false，这里一并挡掉。
                if (!(hi - lo < MAX_TERMS)) return@MathFn Double.NaN
                val scope = values.copyOf(slot + 1)
                var acc = if (product) 1.0 else 0.0
                var k = lo
                while (k <= hi) {
                    scope[slot] = k
                    val term = body.eval(scope)
                    acc = if (product) acc * term else acc + term
                    k += 1.0
                }
                acc
            }
        }

        private fun emitCall(name: String, a: List<MathFn>): MathFn {
            fun one(f: (Double) -> Double): MathFn = a[0].let { x -> MathFn { f(x.eval(it)) } }
            fun two(f: (Double, Double) -> Double): MathFn = MathFn { f(a[0].eval(it), a[1].eval(it)) }
            return when (name) {
                "sin" -> one { kotlin.math.sin(it) }
                "cos" -> one { kotlin.math.cos(it) }
                "tan" -> one { kotlin.math.tan(it) }
                "cot" -> one { 1 / kotlin.math.tan(it) }
                "sec" -> one { 1 / kotlin.math.cos(it) }
                "csc" -> one { 1 / kotlin.math.sin(it) }
                "asin" -> one { kotlin.math.asin(it) }
                "acos" -> one { kotlin.math.acos(it) }
                "atan" -> if (a.size == 2) two { p, q -> kotlin.math.atan2(p, q) } else one { kotlin.math.atan(it) }
                "atan2" -> two { p, q -> kotlin.math.atan2(p, q) }
                "sinh" -> one { kotlin.math.sinh(it) }
                "cosh" -> one { kotlin.math.cosh(it) }
                "tanh" -> one { kotlin.math.tanh(it) }
                "asinh" -> one { kotlin.math.asinh(it) }
                "acosh" -> one { kotlin.math.acosh(it) }
                "atanh" -> one { kotlin.math.atanh(it) }
                "sqrt" -> one(::sqrt)
                "cbrt" -> one { Math.cbrt(it) }
                "abs" -> one { abs(it) }
                "exp" -> one { kotlin.math.exp(it) }
                "ln" -> one { ln(it) }
                "log" -> if (a.size == 2) two { x, b -> ln(x) / ln(b) } else one { ln(it) }
                "lg" -> one { kotlin.math.log10(it) }
                "log2" -> one { kotlin.math.log2(it) }
                "floor" -> one(::floor)
                "ceil" -> one { kotlin.math.ceil(it) }
                "round" -> one { if (it.isNaN()) it else if (it >= 0) floor(it + 0.5) else -floor(-it + 0.5) }
                "sign" -> one { if (it.isNaN()) Double.NaN else kotlin.math.sign(it) }
                "min" -> MathFn { v -> a.minOf { it.eval(v) } }
                "max" -> MathFn { v -> a.maxOf { it.eval(v) } }
                "pow" -> two(::power)
                "mod" -> two { x, b -> x - b * floor(x / b) }
                "hypot" -> two { x, y -> sqrt(x * x + y * y) }
                "fact" -> one(::factorial)
                else -> throw MathSyntaxException("未知函数 $name")
            }
        }

        /** 整数按连乘算（负整数无定义）；非整数取 Γ(x+1)，这样 x! 也能当连续函数画。 */
        private fun factorial(x: Double): Double {
            if (x.isNaN()) return x
            val n = round(x)
            if (abs(x - n) > BOUND_EPSILON) return gamma(x + 1)
            if (n < 0) return Double.NaN
            if (n > 170) return Double.POSITIVE_INFINITY
            var acc = 1.0
            for (i in 2..n.toInt()) acc *= i
            return acc
        }

        private val LANCZOS = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
            1.5056327351493116e-7,
        )

        /** Lanczos 近似（g = 7），x < 0.5 走反射公式。 */
        private fun gamma(x: Double): Double {
            if (x < 0.5) return PI / (kotlin.math.sin(PI * x) * gamma(1 - x))
            val z = x - 1
            var a = LANCZOS[0]
            for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (z + i)
            val t = z + 7.5
            return sqrt(2 * PI) * t.pow(z + 0.5) * kotlin.math.exp(-t) * a
        }

        /**
         * 负底数配分数指数时 pow 给 NaN。写成分数的奇次根（x^(1/3)）仍应画出负半支，
         * 各家图形计算器都这么做。
         */
        private fun power(b: Double, e: Double): Double {
            if (b >= 0 || abs(e - round(e)) < 1e-12) return b.pow(e)
            val reciprocal = 1 / e
            val odd = round(reciprocal)
            if (abs(reciprocal - odd) < 1e-9 && (odd.toLong() and 1L) == 1L) return -(-b).pow(e)
            return Double.NaN
        }

        // ---- LaTeX -------------------------------------------------------------

        private val greek = mapOf(
            "alpha" to "\\alpha", "beta" to "\\beta", "gamma" to "\\gamma", "delta" to "\\delta",
            "epsilon" to "\\epsilon", "theta" to "\\theta", "lambda" to "\\lambda", "mu" to "\\mu",
            "sigma" to "\\sigma", "phi" to "\\varphi", "omega" to "\\omega", "rho" to "\\rho",
            "tau" to "\\tau", "kappa" to "\\kappa", "eta" to "\\eta", "xi" to "\\xi", "psi" to "\\psi",
        )

        private fun precedence(node: Node): Int = when {
            node is Bin && (node.op == '+' || node.op == '-') -> 1
            node is Bin && (node.op == '*' || node.op == '/') -> 2
            // ∑ 向右吞掉整个求和体，只在乘积里并列不加括号。
            node is Iter -> 2
            node is Neg -> 3
            node is Bin && node.op == '^' -> 4
            else -> 5
        }

        private fun latex(node: Node, parent: Int): String {
            val text = when (node) {
                is Num -> formatNumber(node.value)
                is Const -> when (node.name) {
                    "pi", "PI", "π" -> "\\pi"
                    "tau", "τ" -> "\\tau"
                    else -> "e"
                }
                is Var -> variableLatex(node.name)
                is Neg -> "-" + latex(node.operand, 3)
                is Bin -> when (node.op) {
                    '+' -> leftLatex(node.left, 1) + " + " + latex(node.right, 1)
                    '-' -> leftLatex(node.left, 1) + " - " + latex(node.right, 2)
                    '*' -> {
                        val tight = (node.implicit && !(node.left is Num && node.right is Num)) || juxtapose(node)
                        leftLatex(node.left, 2) + (if (tight) " " else " \\cdot ") + latex(node.right, 2)
                    }
                    '/' -> "\\frac{" + latex(node.left, 0) + "}{" + latex(node.right, 0) + "}"
                    '^' -> powerLatex(node)
                    else -> "?"
                }
                is Call -> callLatex(node)
                is Iter -> (if (node.product) "\\prod" else "\\sum") +
                    "_{" + variableLatex(node.index) + "=" + latex(node.from, 0) + "}^{" + latex(node.to, 0) + "} " +
                    latex(node.body, 2)
            }
            val isFraction = node is Bin && node.op == '/'
            return if (precedence(node) < parent && (!isFraction || parent >= 5)) "\\left($text\\right)" else text
        }

        /** 左操作数是 ∑ 时加括号，否则后面的 + 1 读起来像在求和体里。 */
        private fun leftLatex(node: Node, parent: Int): String =
            if (node is Iter) "\\left(" + latex(node, 0) + "\\right)" else latex(node, parent)

        private val trig = setOf("sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh")

        private fun powerLatex(b: Bin): String {
            val exponent = latex(b.right, 0)
            val left = b.left
            if (left is Call && left.name in trig && b.right is Num) {
                return "\\${left.name}^{$exponent}\\left(${latex(left.args[0], 0)}\\right)"
            }
            return latex(b.left, 5) + "^{" + exponent + "}"
        }

        private fun juxtapose(b: Bin): Boolean {
            val l = b.left
            val r = b.right
            val leftOk = l is Num || l is Const || l is Var || (l is Bin && l.op == '^')
            val rightOk = r is Var || r is Const || r is Call ||
                (r is Bin && r.op == '^' && (r.left is Var || r.left is Const))
            return leftOk && rightOk && !(l is Num && r is Num)
        }

        private fun callLatex(c: Call): String {
            val a = c.args.map { latex(it, 0) }
            return when {
                c.name == "sqrt" -> "\\sqrt{${a[0]}}"
                c.name == "cbrt" -> "\\sqrt[3]{${a[0]}}"
                c.name == "abs" -> "\\left|${a[0]}\\right|"
                c.name == "floor" -> "\\lfloor ${a[0]} \\rfloor"
                c.name == "ceil" -> "\\lceil ${a[0]} \\rceil"
                c.name == "exp" -> "e^{${a[0]}}"
                c.name == "fact" -> latex(c.args[0], 5) + "!"
                c.name == "pow" -> latex(c.args[0], 5) + "^{" + a[1] + "}"
                c.name == "log" && a.size == 2 -> "\\log_{${a[1]}}\\left(${a[0]}\\right)"
                c.name == "lg" -> "\\lg\\left(${a[0]}\\right)"
                c.name == "log2" -> "\\log_{2}\\left(${a[0]}\\right)"
                c.name == "log" || c.name == "ln" -> "\\ln\\left(${a[0]}\\right)"
                c.name == "asin" -> "\\arcsin\\left(${a[0]}\\right)"
                c.name == "acos" -> "\\arccos\\left(${a[0]}\\right)"
                c.name == "atan" && a.size == 1 -> "\\arctan\\left(${a[0]}\\right)"
                c.name in trig || c.name == "min" || c.name == "max" ->
                    "\\" + c.name + "\\left(" + a.joinToString(", ") + "\\right)"
                c.name == "mod" -> a[0] + " \\bmod " + a[1]
                else -> "\\mathrm{" + c.name + "}\\left(" + a.joinToString(", ") + "\\right)"
            }
        }

        private fun variableLatex(name: String): String {
            greek[name]?.let { return it }
            if (name.length == 1) return name
            var split = name.length - 1
            while (split > 0 && name[split].isDigit()) split--
            if (split < name.length - 1 && split == 0) return name.substring(0, 1) + "_{" + name.substring(1) + "}"
            return "\\mathit{" + name.replace("_", "\\_") + "}"
        }
    }

    // ---- lex + parse -------------------------------------------------------------

    private enum class TokenKind { NUMBER, IDENT, OP, END }

    private data class Token(val kind: TokenKind, val text: String, val value: Double = 0.0)

    private class Parser(text: String, knownNames: Collection<String>) {
        private val tokens = lex(text)
        private var index = 0
        /** 允许出现的名字；解析求和体时临时加入求和变量。 */
        private val known = HashSet(knownNames)
        private var inIteration = false

        private val peek: Token get() = tokens[index]
        private fun next(): Token = tokens[index++]
        private fun isOp(op: String) = peek.kind == TokenKind.OP && peek.text == op

        fun parseAll(): Node {
            if (peek.kind == TokenKind.END) throw MathSyntaxException("表达式为空")
            val node = parseAdditive()
            if (peek.kind != TokenKind.END) throw MathSyntaxException("无法理解「${peek.text}」")
            return node
        }

        private fun parseAdditive(): Node {
            var left = parseTerm()
            while (isOp("+") || isOp("-")) {
                val op = next().text[0]
                left = Bin(op, left, parseTerm())
            }
            return left
        }

        private fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                left = when {
                    isOp("*") || isOp("/") -> {
                        val op = next().text[0]
                        Bin(op, left, parseUnary())
                    }
                    peek.kind == TokenKind.NUMBER || peek.kind == TokenKind.IDENT || isOp("(") ->
                        Bin('*', left, parsePower(), implicit = true)
                    else -> return left
                }
            }
        }

        private fun parseUnary(): Node {
            if (isOp("-")) { next(); return Neg(parseUnary()) }
            if (isOp("+")) { next(); return parseUnary() }
            return parsePower()
        }

        private fun parsePower(): Node {
            var base = parsePrimary()
            // 阶乘是后缀，比乘方绑得紧：(2k+1)! 、n!^2。
            while (isOp("!")) {
                next()
                base = Call("fact", listOf(base))
            }
            if (!isOp("^")) return base
            next()
            return Bin('^', base, parseUnary())
        }

        private fun parsePrimary(): Node {
            val token = next()
            return when {
                token.kind == TokenKind.NUMBER -> Num(token.value)
                token.kind == TokenKind.IDENT -> parseIdentifier(token.text)
                token.kind == TokenKind.OP && token.text == "(" -> {
                    val inner = parseAdditive()
                    expect(")")
                    inner
                }
                token.kind == TokenKind.OP && token.text == "|" -> {
                    val inner = parseAdditive()
                    expect("|")
                    Call("abs", listOf(inner))
                }
                token.kind == TokenKind.END -> throw MathSyntaxException("表达式不完整")
                else -> throw MathSyntaxException("这里不应出现「${token.text}」")
            }
        }

        private fun parseIdentifier(name: String): Node {
            resolveFunction(name)?.let { return parseCall(name, it) }
            if (name in known) return Var(name)
            constant(name)?.let { return Const(name, it) }
            if (isOp("(") && name.length > 1) throw MathSyntaxException("未知函数 $name")
            // "sinx" → sin(x)；"ax" → a·x。只在每一段都是已知名字时拆，拼写错误仍按错误报。
            return splitRunTogether(name) ?: Var(name)
        }

        private fun parseCall(written: String, function: String): Node {
            // sin^2(x) = (sin x)^2——标准写法，模型也常这么写。
            var power: Node? = null
            if (isOp("^")) {
                next()
                power = parsePrimary()
            }
            if (!isOp("(")) {
                // √x 不带括号的写法和带括号一样常见。
                if (function == "sqrt" && power == null) return Call("sqrt", listOf(parsePower()))
                throw MathSyntaxException("函数 $written 后需要括号，例如 $written(x)")
            }
            next()
            if (function in iterators) {
                val iter = parseIteration(written, function == "prod")
                return if (power == null) iter else Bin('^', iter, power)
            }
            val args = mutableListOf(parseAdditive())
            while (isOp(",")) {
                next()
                args += parseAdditive()
            }
            expect(")")
            val range = functions.getValue(function)
            if (args.size !in range) {
                throw MathSyntaxException(
                    if (range.first == range.last) "$written 需要 ${range.first} 个参数"
                    else "$written 需要 ${range.first}–${range.last} 个参数",
                )
            }
            val call = Call(function, args)
            return if (power == null) call else Bin('^', call, power)
        }

        /** 左括号之后：`k=1, n, 表达式)` 或 `k, 1, n, 表达式)`。 */
        private fun parseIteration(written: String, product: Boolean): Node {
            if (inIteration) throw MathSyntaxException("求和、连乘不能嵌套")
            val usage = "$written 的写法是 $written(k=1, n, 表达式)"
            val head = next()
            if (head.kind != TokenKind.IDENT || resolveFunction(head.text) != null || constant(head.text) != null) {
                throw MathSyntaxException(usage)
            }
            if (!isOp("=") && !isOp(",")) throw MathSyntaxException(usage)
            next()
            val from = parseAdditive()
            expect(",")
            val to = parseAdditive()
            expect(",")
            val added = known.add(head.text)
            inIteration = true
            val body = try {
                parseAdditive()
            } finally {
                inIteration = false
                if (added) known.remove(head.text)
            }
            expect(")")
            val lo = constantValue(from)
            val hi = constantValue(to)
            if (lo != null && hi != null && floor(hi + BOUND_EPSILON) - floor(lo + BOUND_EPSILON) >= MAX_TERMS) {
                throw MathSyntaxException("$written 最多 $MAX_TERMS 项")
            }
            return Iter(product, head.text, from, to, body)
        }

        private fun constantValue(node: Node): Double? = when (node) {
            is Num -> node.value
            is Const -> node.value
            is Neg -> constantValue(node.operand)?.let { -it }
            else -> null
        }

        private fun splitRunTogether(name: String): Node? {
            for (function in functionsByLength) {
                if (name.length > function.length && name.startsWith(function) &&
                    name.substring(function.length) in known
                ) {
                    return Call(function, listOf(Var(name.substring(function.length))))
                }
            }
            if (name.length !in 2..4) return null
            var product: Node? = null
            for (c in name) {
                val part = c.toString()
                val factor: Node = when {
                    part in known -> Var(part)
                    part == "e" -> Const("e", E)
                    else -> return null
                }
                product = if (product == null) factor else Bin('*', product, factor, implicit = true)
            }
            return product
        }

        private fun expect(op: String) {
            if (!isOp(op)) {
                throw MathSyntaxException(if (peek.kind == TokenKind.END) "缺少「$op」" else "此处应为「$op」")
            }
            next()
        }

        private companion object {
            fun lex(text: String): List<Token> {
                val tokens = ArrayList<Token>()
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    if (c.isWhitespace()) { i++; continue }

                    if (c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit())) {
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                        // 只有后面跟数字才算指数——"2e" 是 2 乘 e。
                        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                            var j = i + 1
                            if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
                            if (j < text.length && text[j].isDigit()) {
                                i = j
                                while (i < text.length && text[i].isDigit()) i++
                            }
                        }
                        val literal = text.substring(start, i)
                        val value = literal.toDoubleOrNull() ?: throw MathSyntaxException("数字「$literal」无效")
                        tokens += Token(TokenKind.NUMBER, literal, value)
                        continue
                    }

                    if (c.isLetter() || c == '_') {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                        tokens += Token(TokenKind.IDENT, text.substring(start, i))
                        continue
                    }

                    when (c) {
                        // = 只在 sum(k=1, …) 里有意义；方程的等号在进这里之前已经拆开。
                        '+', '-', '*', '/', '^', '(', ')', ',', '|', '!', '=' -> tokens += Token(TokenKind.OP, c.toString())
                        '[' -> tokens += Token(TokenKind.OP, "(")
                        ']' -> tokens += Token(TokenKind.OP, ")")
                        else -> throw MathSyntaxException("不支持的字符「$c」")
                    }
                    i++
                }
                tokens += Token(TokenKind.END, "结尾")
                return tokens
            }
        }
    }
}
