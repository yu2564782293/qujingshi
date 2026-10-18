# 取景师

不会拍照也能拍出好照片的 AI 相机。不用你找构图，AI 帮你找，并告诉你该怎么拍。

拿起手机，取景师会实时给你：

- **构图引导**：三分线、水平仪，加上"往左移半步、退后一点"的箭头提示，一次只说最重要的一件事
- **姿势建议**：AI 推荐的人物姿势，以虚线人形叠在取景器上，被拍的人走进去对齐就行
- **取景推荐**：AI 给当前取景打美感分，并告诉你往哪个方向调整会更好
- **色彩风格**：按场景自动套用人像/风景/夜景等风格，所见即所得

全部**端侧运行**——离线可用、零延迟、照片不离开手机。

## 技术路线

| 功能 | 技术 |
|---|---|
| 相机 | CameraX |
| 姿势检测 | MediaPipe Pose Landmarker（33 点骨架，多人） |
| 主体判定 | 跨帧追踪＋综合评分（大小/停留/朝向/位置），可点击锁定 |
| 构图引导 | 规则引擎（三分线/留白/不切关节）＋ NIMA 候选取景评分 |
| AI 美学 | NIMA MobileNet（TFLite，AVA 数据集预训练）端侧 |
| 色彩 | CameraX + Media3 GPU 效果，预览与成品同步 |

## 构建

需求：JDK 17+、Android SDK（compileSdk 35）。

```bash
./gradlew assembleDebug
# APK 产出于 app/build/outputs/apk/debug/
```

最低支持 Android 10（API 29）。也可以直接用 GitHub Actions 云端构建（push 即出包）。

## 致谢

本项目 fork 自 [clifftseng/AI-Camera](https://github.com/clifftseng/AI-Camera)（MIT），
核心架构、规则引擎与模型管线源自上游，致谢原作者。

## License

MIT
