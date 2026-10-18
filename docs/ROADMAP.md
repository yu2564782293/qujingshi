# 开发路线图

每一版都是能实际使用的 APK。

## v0.1 — 相机地基 ✅

- CameraX 取景、拍照存到相簿（Pictures/AICamera）
- 三分构图虚线
- 水平仪（±1.5° 内变绿）
- 前后镜头切换

## v0.2 — 姿势虚线 ✅

- MediaPipe Pose Landmarker 即时骨架侦测（on-device，lite 模型内建于 assets）
- 内建姿势库 4 式（自然站姿／单手叉腰／双手举V／侧身斜倚），按钮轮播
- 推荐姿势画成虚线人形叠在取景器上，会自动贴齐侦测到的人物位置与大小
- 图库缩图按钮：显示最新照片，点击直接开启（使用者建议新增）
- minSdk 26 → 29（MediaStore 缩图 API 需要）

## v0.3 — 构图引导 ✅

- 规则引擎（`CompositionAdvisor`）：一次只提示一件最重要的事，优先序
  切到头 > 切在小腿 > 主体对三分线 > 头上空白太多 > 眼睛对上三分线
- 引导箭头（琥珀色）＋文字泡泡；全过就显示绿色「构图 ✓」
- 防抖：新提示连续成立 8 帧（约 0.3–0.5 秒）才显示，避免闪烁
- 肢体入镜判断用 MediaPipe 的 per-landmark visibility
- v0.3.1：支援多人（numPoses=4）＋路人过滤——只把「身高 ≥ 最高者 55%
  且 ≥ 画面高 12%」的人当主体；构图规则看群体（切头／切腿任一人踩到就提醒、
  三分线看群体中心、眼睛线只在单人时启用）；姿势虚线锚定最大主体。
  若误判再加「点击锁定主体」。
- v0.3.2：路人过滤升级成 `SubjectTracker`——跨帧追踪（配 ID、记停留帧数与移动量），
  主体用综合分数判定：大小 40%＋停留稳定度 30%＋面向镜头 20%＋靠近中心 10%，
  拉远景时「站定面向镜头的小人」赢过「走动中的大路人」；绝对门槛放低到画面高 8%。
  加「点击锁定主体」（青色角框标示，再点解除），切镜头时追踪重置。

## v0.4 — 色彩自动套用 ✅

- CameraX + Media3 GPU 效果 pipeline（`androidx.camera.media3:media3-effect`）：
  同一组色彩效果**同时**套在预览与拍出的 JPEG，所见即所得
- 6 种风格：自动／人像／风景／食物／夜景／黑白／原色，左上角按钮轮播
- 自动模式：画面暗（平均亮度 < 0.22）→ 夜景；有人 → 人像；否则 → 风景。
  连续 30 帧（约 1–2 秒）才切换，避免风格跳动
- 不支援效果 pipeline 的机器自动退回原色（防御性 fallback）
- 注：目前用 RGB/HSL/对比参数式调色而非 3D LUT；真正的 adaptive 3D LUT 在 v0.6

## v0.5 — AI 构图模型 ✅

- NIMA（MobileNet aesthetic，idealo 在 AVA 25 万张人评照片上的预训练权重）
  转 float16 TFLite（6.4MB，转换脚本在 `tools/convert_nima.py`），内建于 assets
- 「取景推荐」形态：对目前取景与 5 个候选（左/右/上/下偏移、拉近）各打美感分数，
  候选赢过现况 ≥0.12 分才建议方向——纯风景（没有人）也有效
- 每 2 秒背景跑一轮（6 次推论），画面下方显示「AI 美感 X.X・往左移一点更好」
- 已知怪癖：NIMA 对高频纹理偏好偏高；只用于同场景候选框相对比较，不受影响
- GAIC 专用裁切模型（更准的位置建议）留待未来，需要自训转换（RoIAlign 客制 op）
- v0.5.1：色彩统计自适应——每 15 帧抽样亮度/饱和/对比/色温，动态调各风格强度
  （鲜艳场景少加饱和、已偏暖不再加暖、夜景提亮看实际暗度），统计明显变化才重套

## v0.6 — AI 色彩模型 ✅

- Image-Adaptive 3D LUT（Zeng et al. TPAMI 2020，sRGB paired 预训练，FiveK 资料集）
- 拆成两半上机：权重预测 CNN → float16 TFLite（0.56MB，Keras 手工移植，
  与 PyTorch 参考输出差 <1e-3）；3 个基底 LUT → `luts_basis.bin`（1.3MB）
- 转换脚本 `tools/convert_lut3d.py`（含与 torch 的自动比对验证）
- App 端：CNN 每 2 秒预测 3 个混合权重 → CPU 混出 33³ LUT →
  Media3 `SingleColorLut` 套用（预览＋成品同步）；权重变化 >0.05 才重建
- 色彩模式新增「AI 调色」（自动 → AI 调色 → 人像 → …）
- fine-tune 资料集候选（未做）：PPR10K（人像）、AVA（美学）

## v0.6.1 / v0.7.0 — 实测回馈修正 ✅

- v0.6.1：横向支援（configChanges 不重启、targetRotation 跟转、水平仪补偿萤幕方向）
- v0.7.0：两种引导模式（右上第二颗按钮切换）——
  **移镜头**：虚线人形贴著人，构图箭头引导摄影者；
  **移人**：取景当作不动，虚线人形画在最近的三分线位置（带迟滞防跳动），
  请被拍的人走进去，对齐 ±4% 内虚线变绿。切到移人模式若没开姿势会自动开第一式
- v0.7.0：色彩管线健壮化——绑定失败改成**分级降级并明说**：
  预览＋拍照都有色 → 只有预览有色（提示成品为原色）→ 完全停用（隐藏按钮＋提示），
  不再默默退回无色彩让人以为调色没效果；管线中途出错也会提示并停用

## 设计原则

1. **全 on-device**：不接云端 API，离线可用、零延迟、隐私干净
2. **不从零训练**：优先用预训练模型；不够好才拿公开资料集 fine-tune
   （AVA / GAICD / PPR10K），最后才考虑自建风格资料集
3. **打分模型没办法变成引导，取景推荐模型才可以**：
   构图 AI 一律做成「对候选框打分→最佳框→引导方向」的形态
4. **风景 vs 人像用场景分类路由**，共用同一个构图模型，不分开训练

## 参考

- [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [GAIC: Grid Anchor based Image Cropping](https://github.com/bcmi/Awesome-Aesthetic-Evaluation-and-Cropping)
- [NIMA: Neural Image Assessment](https://github.com/idealo/image-quality-assessment)
- [Learning Image-Adaptive 3D Lookup Tables](https://github.com/HuiZeng/Image-Adaptive-3DLUT)
- [android-gpuimage-plus](https://github.com/wysaid/android-gpuimage-plus)
