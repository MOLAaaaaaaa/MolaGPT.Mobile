package com.molagpt.app.core.render

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 键盘弹出时，让系统返回**先收起键盘**，而不是退出当前页面／关闭当前弹层。
 *
 * ## 为什么 App 得自己兜底
 *
 * 「返回先收键盘」在 AOSP 里是**输入法进程**的职责，不是 App 的：
 * - 传统路径：`InputMethodService.onKeyDown` 见到 `KEYCODE_BACK` 且输入视图可见时自己
 *   `handleBack()` 并吃掉事件，App 根本收不到。
 * - 预测式返回路径（本项目 manifest 开了 `enableOnBackInvokedCallback`）：输入法通过
 *   `ImeOnBackInvokedDispatcher` 把自己的 `OnBackInvokedCallback` 转发注册进 App 窗口的
 *   dispatcher，并排在 App 回调之前。
 *
 * 两条路径都要求输入法／系统实现完整。Gboard + Pixel 正常；**三星机型未启用预测式返回**，
 * 返回会直接穿透到 App 的 NavHost，于是一次手势既收了键盘又退了页面（2026-07 实测确认）。
 * 所以凡是带输入框的页面／弹层都要挂这个兜底。
 *
 * ## 两边都不亏
 *
 * | | 键盘弹出 | 键盘收起 |
 * |---|---|---|
 * | 输入法会吃返回（Pixel） | 轮不到本 handler，等同 no-op | `enabled=false`，预测式转场完好 |
 * | 输入法不吃返回（三星） | 本 handler 收起键盘 | `enabled=false`，行为不变 |
 *
 * 因为只在键盘可见时 `enabled`，**不会**违反 `MolaNavHost` 那条「目标页面不应额外拦截
 * BackHandler」的约定——键盘收起时它是禁用的，NavHost 可 seek 的返回转场不受影响；
 * 键盘弹出时接管也正是想要的（那种时候本来就不该让页面跟着手指预览滑动）。
 *
 * ## 调用位置有讲究
 *
 * `OnBackPressedDispatcher` 是 **LIFO**：越晚注册的回调越先收到。Compose 里 composition
 * 顺序越靠后／越内层的 `BackHandler` 优先级越高。因此：
 * - 屏幕里若还有别的 `BackHandler`（如「退出会话」），本函数必须写在**它们之后**，否则
 *   键盘弹着时会被那个先抢走。见 `AgentControlScreen` 的调用点注释。
 * - `ModalBottomSheet` 里写在 content lambda 内即可——sheet 自身的关闭回调注册得更早，
 *   天然低一级。
 *
 * `ChatScreen` 未调用本函数：它把 IME 和「关模型菜单／开新对话」编在同一条 if/else 链里，
 * 逻辑等价，改动它没有收益。
 *
 * @param enabled 额外的启用开关，与「键盘可见」取与。默认 true。
 */
@Composable
fun ImeDismissBackHandler(enabled: Boolean = true) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled = enabled && imeVisible) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
}
