# 新 Agent 交接：Android FSSA v1.3.4 移植

> 交接日期：2026-09-11
>
> 工作目录：`/home/labulubius/Projects/Fungal-Sentinel`
>
> 当前状态：Android v1.3.4 已整理并发布；旧版 `group/` 参考资料目录已按用户要求移除

## 新 Agent 首先阅读

1. 本文件；
2. `docs/python-v1.3.4-port.md`；
3. `docs/software-review-issues.md`；
4. Android 核心：`SpectralAlgorithms.kt`、`RawProfileExtractor.kt`、`SpectralModels.kt`、`MainActivity.kt`。

## 用户目标

将 Android App 的核心光谱算法与 Python FSSA v1.3.4 基准实现对齐，同时保留适合 Camera2 即时采集的交互和设备安全检查。用户确认：不与 FSSA 核心算法冲突的可靠性和质量控制可以继续实现。

## 已完成的 v1.3.4 核心算法

- RAW 黑电平扣除后按实际曝光时间归一化；
- Positioning、SPD、Blank、Sample 和标准品组均支持 1–5 帧；
- Step 1 第一帧自动识别并锁定 X-ROI，所有后续帧复用；
- B/R 两点拟合、G 留出验证；
- 正负波长斜率均转换成递增波长数组；
- SPD 插值、420–680 nm 掩码、SPD 峰值 5% 阈值；
- 响应除数与 Python 一致为 `SPD + 1e-6`；
- Savitzky–Golay `(window=21, polynomial=2, mode=nearest)`；
- Blank 先平均，每张 Sample 分别执行 `clip(Sample - AvgBlank, 0)`；
- 响应修正后按目标峰 FWHM 区间进行梯形积分；
- Sample replicate area 的 mean 和 sample SD；
- 2–10 个标准组，每组独立 Blank/Sample 1–5 帧；
- 标准组 mean area 回归和 sample-SD 误差棒；
- 普通最小二乘、R²、未知浓度反算及范围外标记。

## Android 有意保留的底层差异

- Python 固定黑电平 64；Android 使用 Camera2 动态/静态、CFA 位置相关黑电平；
- Python 用 OpenCV `COLOR_BayerRG2RGB` 全图去马赛克；Android 根据 RGGB/GRBG/GBRG/BGGR 直接提取一维 CFA profile，节省手机内存；
- Android 额外检查 camera ID、RAW 尺寸、CFA、ISO 和焦距一致性；曝光时间允许变化；
- Android 使用即时 Camera2 拍摄批次，而 Python 先选择 DNG 文件再 Run；
- Android 自动加载内置 `true_spd.csv`，但它仍必须与实际测量光源匹配。

## 已完成的质量与稳定性修复

- G 验证误差：`≤2.5 nm PASS`、`2.5–10 nm WARNING`、`>10 nm FAILED`；FAILED 阻止 Step 2；
- 饱和比例：`<0.1% PASS`、`0.1%–1% WARNING`、`≥1%` 拒绝加入批次，但 DNG 保留；阈值需要真机实验确认；
- 标准组第一次拍摄时锁定浓度；两个 draft 批次均清空后解除；
- 标准浓度必须有限且非负；
- 回归不改变 Python OLS 输出，但对两浓度水平、非正斜率、低 R²、负预测和范围外预测显示警告；
- RAW 处理从 Camera HandlerThread 移到独立单线程执行器；
- `RawCapture` 显式持有 Image/ImageReader lease，处理完成后释放；
- Camera 拍摄同步异常、断连、session 失败和 generation 清理已加固；
- AE lock 超时 8 秒；RAW 超时至少 15 秒，长手动曝光自动延长为曝光时间加 5 秒；
- Release 不再回退 debug 签名；缺少私有 keystore 时 Release 打包失败，Debug 仍可构建；
- 摄像头优先选择后置 RAW + MANUAL_SENSOR，其次后置 RAW；
- Bayer 缺失行使用有效性掩码，不再把真实 `0.0` 当作缺失值；
- 相机权限拒绝后有持久页面、重试和系统设置入口；
- Capture 按钮提示 Auto 模式需要 Meter & lock；无 AE Lock 的 RAW 设备允许 Auto，但 ISO 变化的后续帧会被拒绝；
- Step 1 显示 positioning 平均 profile，不再误用最后一个其他批次 profile；
- Sample UI 明确：面积/SD 是全部 replicate，曲线和 peak 是最后一帧；
- Positioning/Response Clear 按钮标明会清除下游数据。

## 当前验证基线

最后成功执行：

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
git diff --check
```

结果：

- JVM 单元测试：51 项通过，0 失败；
- `lintDebug`：通过；
- `assembleRelease`：通过；
- `git diff --check`：通过；
- App 元数据：`versionName 1.3.4`、`versionCode 6`。

Release 优化仍为：

```kotlin
optimization {
    enable = false
}
```

尝试启用后 AGP 报错：必须设置实验性的 `android.r8.gradual.support`。已恢复为 false，不要在没有单独验证的情况下再次启用。

## 主要新增文件

- `app/src/main/java/org/fungalsentinel/app/AnalysisQualityPolicy.kt`
- `app/src/main/java/org/fungalsentinel/app/CameraSelection.kt`
- `app/src/main/java/org/fungalsentinel/app/RawCapture.kt`
- `app/src/test/java/org/fungalsentinel/app/AnalysisQualityPolicyTest.kt`
- `app/src/test/java/org/fungalsentinel/app/CameraSelectionTest.kt`
- `docs/python-v1.3.4-port.md`
- `docs/software-review-issues.md`
- 本交接文件

## 下一步优先事项

### 1. 真机完整验收

在 vivo V2303A 或其他 RAW 真机验证：

- 手动 400 ms、ISO、无穷远是否真实写入 metadata；
- Auto → Meter & lock 是否能在 8 秒内完成；
- 不支持 AE Lock 的设备在 ISO 稳定时是否可完成流程；
- RAW 15 秒/长曝光超时；
- 拍摄时切后台、锁屏返回、连续暂停恢复；
- DNG 是否可由常见软件读取；
- Positioning 1–5 → SPD 1–5 → Blank/Sample → 至少三个标准组的完整流程；
- 0.1%/1% 饱和阈值是否合理。

### 2. Python/Android golden-data 数值对照

这是确认“核心算法数值一致”的关键，当前尚未完成：

1. 固定一组真实 DNG；
2. Python 导出每阶段中间数组；
3. Android/独立 JVM 测试读取等价 profile；
4. 比较 ROI、RGB profile、波长映射、响应曲线、净光谱、积分面积、标准组 mean/SD 和预测浓度；
5. 单独量化“OpenCV 去马赛克”和“Android CFA 直接 profile”的偏差。

### 3. 尚未完成的产品项

- 半屏模式及前摄遮挡检测；
- 实验会话持久化（旋转/进程重建目前会丢失批次）；
- Clear 确认弹窗或撤销，目前只有影响范围文字；
- 内置 SPD 的光源型号、测量设备、日期、条件和版本溯源；
- 权限页、批次流程和超时的 Compose/真机自动化测试；
- AE/RAW timeout 的纯状态单元测试；
- 低信号、ROI 稳定性等更多质量门槛；
- 用户反馈“第三个图是什么东西”仍缺截图，不能确定对象。

## 资料目录说明

旧版 `group/` 目录包含 Python 原型、辅助工具和历史材料，已在 Android 功能兑现后按用户要求从仓库移除。Android 移植差异与验收状态保留在 `docs/` 中。

## 注意事项

- Android v1.3.4 发布代码已整理，提交前后仍需检查 `git status`；
- 私有 `keystore.properties` 和 `.jks` 已被 `.gitignore` 忽略，禁止提交；
- P0 中回归质量目前采用“保留 Python 原始结果 + 明确警告”，没有硬删除负斜率或低 R² 结果，这是为了不改变 Python v1.3.4 的 OLS 数学输出；
- 无 AE Lock 的 Auto 模式允许拍摄，但 ISO 不一致会被 metadata 检查拒绝，这是“自动曝光可用”和“定量可比性”之间的折中；
- `createCaptureSession` 有 deprecated 编译警告，但当前构建和测试通过，迁移新 API 应单独处理。
