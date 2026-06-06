package com.molagpt.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 生成 baseline profile，优化冷启动与关键交互热路径。
 *
 * 运行：`./gradlew :baselineprofile:generateBaselineProfile`（需连接真机/模拟器，minSdk 28+）。
 * 产物自动合入 :app（build.gradle.kts 已声明 baselineProfile(project(":baselineprofile"))）。
 *
 * 覆盖场景：冷启动进聊天页 → 打开会话抽屉 → 滚动分页会话列表 → 关闭抽屉 →
 * 轻量滚动消息区。后续可继续补发送消息、流式响应、设置页和复杂 Markdown 渲染。
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.molagpt.app") {
        // 1) 冷启动进首页
        pressHome()
        startActivityAndWait()

        // 2) 等待首帧渲染（登录页或聊天页）。
        device.waitForIdle()

        // 3) 打开会话抽屉，覆盖侧边栏首次组合、Room/Paging 首页加载、图标与列表热路径。
        device.findObject(By.desc("会话列表"))?.click()
        device.wait(Until.hasObject(By.text("新建对话")), 3_000)

        // 4) 滑动会话列表，覆盖 Paging append、LazyColumn item 复用、分组 header 与行项目。
        val sessionList = device.findObject(By.desc("会话列表内容")) ?: device.findObject(By.scrollable(true))
        sessionList?.let { list ->
            repeat(3) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            repeat(2) {
                list.fling(Direction.UP)
                device.waitForIdle()
            }
        }

        // 5) 关闭抽屉，覆盖自定义 spring 收起动画和卸载路径。
        device.pressBack()
        device.waitForIdle()

        // 6) 轻量滚动消息区，覆盖聊天列表基础滚动热路径。
        device.findObject(By.scrollable(true))?.let { scrollable ->
            scrollable.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }
}
