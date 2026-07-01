@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MolaGPT"

// ——— app 宿主 ———
include(":app")

// ——— core 层（单向：feature → core，core 永不依赖 feature）———
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:storage")
include(":core:markdown")
include(":core:render")

// ——— feature 层 ———
include(":feature:chat")
include(":feature:agent-control")
include(":feature:session")
include(":feature:settings")
include(":feature:auth")
include(":feature:webview")
include(":feature:file")
include(":feature:share")

// ——— 性能（baseline profile 生成器）———
include(":baselineprofile")
