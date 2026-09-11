# Fungal Sentinel 中文使用教程

[English README](README.md)

Fungal Sentinel 是一款离线 Android Camera2 RAW 荧光光谱分析软件。

它不是普通相机，也不是直接根据照片识别真菌的 AI。软件需要配合固定的狭缝、光栅、样品架和光源，将手机 RAW 传感器变成便携式光谱检测系统，用于校准波长、校正相机光谱响应、测量荧光积分面积，并通过标准曲线估算未知样品浓度。

## 主要功能

- Camera2 实时预览及 `RAW_SENSOR` 捕获；
- 手动曝光、ISO、焦距、白平衡、降噪、锐化和坏点控制；
- 与 Python FSSA v1.3.4 对齐的四步分析流程；
- 每个批次支持 1–5 次捕获并自动平均；
- 支持 Ypet、EGFP、mCherry、CFP 和 mTurquoise2；
- 校准、饱和及标准曲线质量提示；
- 三种 DNG 保存模式；
- 未完成实验草稿恢复；
- Room History、普通 ZIP 导出及 JSON/CSV 数据；
- 所有计算均在手机本地完成。

## 1. 实验前准备

至少需要：

- 支持 Camera2 RAW 输出的 Android 手机；
- 固定的狭缝、光栅、手机和样品架；
- 已知波长的 R/G/B 定位光源；
- 与真实 SPD CSV 匹配的标准光源；
- Blank（空白）和未知 Sample（样品）；
- 如需测浓度，还需要至少 2 个不同浓度标准组，推荐 3–5 组。

默认定位波长：

- R：`622.5 nm`
- G：`522.5 nm`
- B：`462.5 nm`

实际定位光源波长不同时，必须在 Step 2 中填写真实数值。

> 内置 `true_spd.csv` 只适用于测量该文件时使用的标准光源，不是任意白灯的通用 SPD。来源不匹配时可以测试流程，但不能把结果当作经过验证的正式定量数据。

## 2. 页面和拍摄设置

侧边栏顺序为：

```text
Analyze
Parameters
Capture
Reset
History
Settings
```

### Parameters

此处只调整相机拍摄参数：

- 建议固定手机、ISO、焦距和光学结构；
- 焦距通常设为 `∞`；
- 曝光应尽量稳定并避免过曝；
- 软件会按实际曝光时间归一化，但 ISO、焦距、摄像头、RAW 尺寸和光路必须保持一致；
- 操作范围会与手机真实能力取交集：曝光 `10–3000 ms`、ISO `50–1600`、焦距 `0–5 D`。

### Settings

DNG 模式：

- **All RAW captures**：保存所有有效步骤的 DNG，正式实验推荐；
- **Samples only**：只保存未知 Sample 和标准 Sample DNG；
- **No DNG**：只在内存分析，不保留传感器 RAW，适合流程测试。

Settings 还提供 **Save diagnostic log**。日志仅保存在本机，不会自动上传，也不包含 RAW 图像或项目名称；测试人员可在出现问题时手动保存并发送日志。

每个拍摄批次允许 `1–5` 张。拍 1 张即可运行；一般建议 3 张，弱信号或噪声较大时可拍 5 张。

侧边栏 **Capture** 拍摄的是 Analyze 详情面板中当前选中的批次，并不是普通照片。

## 3. Step 1 — Project name

首次打开或完成上一项目后，进入 Analyze 的 Step 1 输入项目名称，然后点击 **Create project & continue**。名称在该项目中保持固定，并作为 History 记录名和每个 DNG 的前缀，例如：

```text
EGFP样品A__positioning_1_时间戳.dng
EGFP样品A__sample_1_时间戳.dng
```

创建后软件自动进入 Step 2。要创建另一个项目，应先使用 Reset 结束当前项目。

## 4. Step 2 — Wavelength calibration

**目的：确定传感器像素对应的实际波长。**

操作：

1. 接入已知波长的 R/G/B 定位光源；
2. 确认页面中的 R/G/B 波长正确；
3. 选择 **Positioning captures**；
4. 点击 Capture，拍摄 1–5 张，建议 3 张；
5. 查看 Mapping、G validation error 和 Calibration quality。

软件使用 B、R 峰拟合波长映射，用 G 峰独立验证。第一张有效 RAW 会锁定 X-ROI，后续步骤必须保持光路位置不变。

质量等级：

- `PASS`：G 误差绝对值 `≤ 2.5 nm`；
- `WARNING`：误差 `> 2.5 nm` 且 `≤ 10 nm`；
- `FAILED`：误差 `> 10 nm` 或结果无效。

默认 FAILED 会阻止 Step 3。如果目前没有完整校准条件、只想检查软件流程，可进入：

```text
Settings → Calibration protection → 关闭
```

关闭后仍然必须完成 Step 2，而且结果继续标记为 FAILED，只能用于流程测试。

## 5. Step 3 — Spectral response

**目的：校正手机 R/G/B 通道对不同波长的灵敏度差异。**

操作：

1. 保持手机、光栅、狭缝、ISO 和焦距不变；
2. 使用与 SPD 数据匹配的标准光源；
3. 使用内置 SPD，或通过 **Import custom CSV** 导入 `wavelength_nm,intensity` 两列 CSV；
4. 选择 **SPD captures**；
5. 拍摄 1–5 张，建议 3 张；
6. 确认页面生成响应曲线。

更换手机、摄像头、ISO、焦距、RAW 尺寸或光路结构后，应重新执行 Step 2 和 Step 3。

## 6. Step 4 — Sample analysis

**目的：扣除背景并测量未知样品荧光积分面积。**

操作：

1. 选择目标荧光物：Ypet、EGFP、mCherry、CFP 或 mTurquoise2；
2. 放入不含目标荧光物的 Blank；
3. 选择 **Blank captures**，拍摄 1–5 张，建议 3 张；
4. 在相同条件下放入未知 Sample；
5. 选择 **Sample captures**，拍摄 1–5 张，建议 3 张；
6. 查看 Integrated area、Sample SD、Replicates 和光谱图。

计算过程：

```text
Sample RAW − 平均 Blank RAW
→ 光谱响应校正
→ 目标荧光波段积分
→ 重复样品的平均面积和样本标准差
```

只有 1 张 Sample 时也能分析，但 SD 会显示 `0`。这表示没有足够重复数据估算离散程度，不表示实验误差为零。

如果只比较样品的相对荧光强度，可以在 Step 4 结束，不必执行 Step 5。

## 7. Step 5 — Concentration

**目的：建立浓度—荧光面积曲线并估算未知样品浓度。**

每个已知浓度标准品分别操作：

1. 输入 **Standard concentration**；
2. 选择 **Draft standard Blank**，拍摄 1–5 张，建议 3 张；
3. 放入该浓度的标准 Sample；
4. 选择 **Draft standard Sample**，拍摄 1–5 张，建议 3 张；
5. 点击 **Add standard group**；
6. 更换下一个浓度并重复。

至少需要 2 个不同浓度，推荐 3–5 个。所有浓度必须使用相同单位，例如全部为 `mg/L` 或全部为 `µmol/L`。添加完成后点击 **Build curve**。

页面会显示：

- 回归公式；
- R²；
- 未知样品预测浓度；
- 负斜率、低 R²、负预测或超出标定范围警告。

2 个浓度虽然可以计算直线，但不能可靠评价线性，正式实验不推荐只使用 2 个点。

## 8. 一次实验要拍多少张？

这里的“张”指 RAW 捕获次数。选择 No DNG 时次数相同，只是不生成 DNG 文件。

| 场景 | Positioning | SPD | 未知 Blank | 未知 Sample | 标准组 | 每组标准 Blank + Sample | 总计 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 最小 Step 4 流程测试 | 1 | 1 | 1 | 1 | 0 | 0 | **4 张** |
| 推荐 Step 4 分析 | 3 | 3 | 3 | 3 | 0 | 0 | **12 张** |
| 最小完整浓度流程 | 1 | 1 | 1 | 1 | 2 | 1 + 1 | **8 张** |
| 一般定量实验 | 3 | 3 | 3 | 3 | 3 | 3 + 3 | **30 张** |
| 五点标准曲线 | 3 | 3 | 3 | 3 | 5 | 3 + 3 | **42 张** |
| 软件理论上限 | 5 | 5 | 5 | 5 | 10 | 5 + 5 | **120 张** |

建议：

- 只测试软件：每批次 1 张；
- 一般测量：每批次 3 张；
- 弱信号或噪声较大：每批次 5 张；
- 一般浓度实验：3–5 个浓度组，每组 Blank/Sample 各 3 张。

过曝、元数据不一致或分析失败后的重拍不计入表格，因此实际 Capture 次数可能更多。

完整建标确实需要较多捕获；如果手机、光路、光源和参数完全不变，日常检测理论上应复用已经验证的校准和标准曲线，只拍新的 Blank 和 Sample，即每个样品约 2–6 张。当前 History 已保存这些数据，但“载入为校准模板”仍属于后续改进方向。

## 9. 饱和、清空和 Reset

ROI 饱和比例：

- `< 0.1%`：PASS；
- `0.1%–1%`：WARNING；
- `≥ 1%`：拒绝加入批次，应降低曝光或 ISO 后重拍。

每个批次旁的 Clear 会清除该批次和依赖它的结果。

侧边栏 **Reset** 会在确认后开始新实验，清除当前校准、捕获、标准品和未保存结果，但保留：

- 相机参数；
- 波长输入；
- 当前 SPD；
- 已保存 History；
- 已发布到相册目录的 DNG。

## 10. 草稿、History 和 ZIP

软件会自动保存未完成实验草稿。旋转屏幕或重新启动后会直接恢复并继续，不再额外询问。需要放弃当前实验时，用户可主动点击 **Reset**。

Step 5 成功建立浓度曲线后，可以点击 **Finish project & save to History**。项目名称在实验开始时已经创建，不需要结束时再次填写。History 支持查看、删除和导出普通 ZIP。

ZIP 内容：

```text
manifest.json
result.json
profiles.csv
standards.csv
spd.csv
raw/                 # 仅在 DNG 已保存且仍可读取时存在
```

删除 History 会删除应用内部 ZIP，但不会删除 `Pictures/FungalSentinel` 中已经发布的 DNG。对未完成项目执行 Reset 时，软件会明确询问是保留还是删除该项目关联的 DNG，不会静默删除。

## 11. 正式实验注意事项

- 模拟器只能验证界面和流程，不能验证 RAW 光谱准确性；
- 正式定量前应使用同一批 DNG 与 Python FSSA v1.3.4 对照；
- 正式实验建议使用 All RAW captures；
- 必须记录 SPD 来源、光源型号、浓度单位、光学结构和环境条件；
- Calibration protection 关闭时，FAILED 校准的下游结果只能用于流程测试。

## 安装

从 GitHub Releases 下载 APK 并安装。Android 可能要求授权“安装未知应用”。普通用户不需要克隆源代码。

## 开发构建

可以使用 Android Studio，或在 Windows PowerShell 中执行：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleRelease
```

Release APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

正式发布必须妥善备份签名文件，并且不能将真实 keystore 和密码提交到 GitHub。
