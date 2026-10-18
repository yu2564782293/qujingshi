# 取景师

AI 自动找构图、告诉用户怎么拍的 Android 相机。fork 自 clifftseng/AI-Camera（MIT）。

## 硬规则

- 全端侧：不新增任何云端 API 依赖（拍后点评这类 v2 功能例外，另行评审）
- UI 文案一律简体中文，引导语要口语化（"往左移半步"而不是"请遵循三分法"）
- 引导一次只说一件事；新提示必须防抖（沿用 CompositionAdvisor 的 8 帧机制）
- 不要提交任何凭证、token、私钥到仓库
- 版本号在 `app/build.gradle.kts`（versionCode / versionName），每次发版同步 bump

## 构建

- `./gradlew assembleDebug`（需 JDK 17+、Android SDK compileSdk 35）
- 本地沙箱跑不了 Gradle Daemon，构建走 GitHub Actions（`.github/workflows/build.yml`）
