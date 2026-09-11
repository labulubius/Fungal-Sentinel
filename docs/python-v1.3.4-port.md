# Python FSSA v1.3.4 Android 移植记录

> 实施日期：2026-09-11
>
> 基准实现：`group/FSSA_v1.3.4.py`

## 已移植算法

- RAW 黑电平扣除后按实际曝光秒数归一化；缺失有效曝光元数据时拒绝分析；
- Step 1 首帧自动识别 X-ROI，之后所有 Positioning、SPD、Blank、Sample 和标准品帧复用该 ROI；
- Positioning 和 SPD 各支持 1–5 帧逐像素平均；
- 波长校准继续使用 B/R 两点拟合与 G 留出验证，并支持正、负斜率光路；
- 响应校准使用 420–680 nm 范围、SPD 峰值 5% 阈值、`SPD + 1e-6` 除数及 Savitzky–Golay `(21, 2, nearest)` 平滑；
- Step 3 支持 Blank 1–5 帧和 Sample 1–5 帧：平均 Blank，对每个 Sample 分别执行 `clip(Sample - AvgBlank, 0)`，再进行响应校正和梯形积分；
- 未知样品输出 replicate area 的均值与样本标准差；
- Step 4 支持 2–10 个标准组，每组包含 1–5 Blank 和 1–5 Sample；
- 标准曲线使用各组平均面积回归，并绘制样本标准差误差棒；
- 波长、SPD 或不兼容相机参数改变时清除依赖旧条件的批次和结果；仅曝光时间变化不会清除结果，因为信号已经完成曝光归一化。

## Android 交互方式

Python 版先选择文件再点击 Run；Android 版使用 Camera2 即时采集，因此采用以下等价操作：

- 每拍摄一帧即追加到当前选中的批次；
- Positioning/SPD 在每次追加后用当前 1–5 帧重新计算；如果当前帧数仍不足以完成校准，已提取的帧会保留，可继续拍摄；
- Sample 页面先选择 Blank 或 Sample 批次，再使用侧栏 Capture；两类批次都有数据后自动重新分析；
- Concentration 页面填写浓度，分别采集当前标准的 Blank/Sample，点击 Add standard group 后保存该组 Mean±SD，并开始下一组；
- 每个批次可以单独 Clear，已添加标准组可以单独 Remove。

## 有意保留的 Android 差异

以下差异是平台适配，不改变 v1.3.4 的上层物理顺序：

- Python 使用固定黑电平 64；Android 优先使用 Camera2/DNG 动态或 CFA 位置相关黑电平；
- Python 固定按 Bayer RG 使用 OpenCV 全图去马赛克；Android 根据相机报告的 RGGB/GRBG/GBRG/BGGR 直接提取一维 CFA profile，以降低手机内存占用；
- Android 额外要求 camera ID、RAW 尺寸、CFA、ISO 和焦距一致。曝光时间允许不同；
- 不支持手动曝光且无法 AE Lock 的 RAW 设备仍可自动曝光采集，但批次元数据一致性检查会拒绝 ISO 发生变化的后续帧；
- Android 自动加载内置 SPD，但该 SPD 仍只能与其实际测量光源配套使用。

## 验证

- `./gradlew testDebugUnitTest lintDebug assembleRelease`：通过；
- JVM 单元测试：51 项通过，0 失败；
- 已覆盖多帧平均、Blank/Sample 扣除、负斜率、SG `(21,2)`、曝光归一化、固定 ROI、标准组 Mean/样本 SD、最多 10 组及误差棒范围；
- `git diff --check`：通过。

## Android 采集质量包装层

这些检查不改变 Python v1.3.4 的曝光归一化、Blank 扣除、响应校正、积分和 OLS 公式：

- G 留出误差大于 10 nm 时阻止进入 Step 2；
- ROI 饱和比例 0.1% 起警告，1% 起拒绝加入分析批次，但保留 DNG；
- 标准草稿第一帧开始时锁定非负浓度，避免拍摄后重标；
- 负斜率、低 R²、两浓度水平、负预测和范围外预测保留原始数值并显示质量警告；
- 自动测光锁定 8 秒超时，RAW 捕获至少 15 秒超时（长曝光会自动延长）；
- 阈值仍需通过真实光学系统验证。

## 尚待真机验证

- 1–5 帧各批次的完整 UI 操作；
- 不同曝光时间下曝光归一化结果的一致性；
- 自动曝光锁定设备的实际 ISO 稳定性；
- 高分辨率 RAW 连续处理、DNG 可读性和暂停/恢复；
- Android CFA 直接 profile 与 Python OpenCV 去马赛克 profile 在同一组 DNG 上的数值偏差；
- 使用真实 Blank、标准品和已知浓度样本进行端到端结果对照。
