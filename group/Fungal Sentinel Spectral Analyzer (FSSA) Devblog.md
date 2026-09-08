**Fungal Sentinel Spectral Analyzer (FSSA)** 是一款专为低成本便携式荧光检测仪设计的开源光谱分析软件。它利用智能手机摄像头的 RAW (DNG) 数据，结合光栅分光原理，实现高精度的荧光光谱重建与定量分析。

本项目秉持 **“节俭科学 (Frugal Science)”** 与 **“边缘计算 (Edge Computing)”** 理念，采用**完全脱机 (Offline-First)** 架构，确保在零网络环境下依然能够完成从图像采集到浓度预测的全流程，彻底保障生物数据隐私。

注：本软件与团队的硬件配套使用。

硬件与整体项目链接：荧光检测仪软硬件 

其他软件项目的介绍：CSV Data Cleaner Introduction 

# 使用说明 (User Guide)

适配v1.3.3， 更新于2026-09-06

### 1. 环境要求 (Requirements)

- **硬件**：支持拍摄 RAW/DNG 格式的智能手机（推荐 Android 设备以获取底层 Camera2 API 控制权），或任意可运行 Python 3.8+ 的 PC。
- **网络**：**完全脱机 (Offline-First)**。本软件为纯本地运行，无需互联网连接，确保生物数据隐私。
- **依赖库 (Python 版)**：*(注：GUI 界面依赖 Python 内置的 `tkinter`，部分 Linux 系统可能需要额外安装 `python3-tk`)*
    
    ```bash
    pip install numpy scipy opencv-python matplotlib tifffile
    ```
    

### 2. 数据准备 (Data Preparation)

在进行样本检测前，请准备好以下 DNG (RAW) 图像及辅助文件。软件支持**单次上传 1-5 张图像进行平均降噪**，强烈建议对同一状态进行多次拍摄以提升信噪比。

- **`positioning.dng` (1-5张)**：定位光源（R/G/B 三色 LED）的 RAW 图像，用于波长映射。
- **`spd.dng` (1-5张)**：标准校准光源（如白光 LED）的 RAW 图像，用于计算相机光谱响应。
- **`blank.dng` (1-5张)**：空白背景（如纯缓冲液/空比色杯）的 RAW 图像，用于扣除系统暗电流与溶剂拉曼背景。
- **`sample.dng` (1-5张)**：待测样本的 RAW 图像。
- **`std_*.dng` (可选)**：已知浓度的标准品图像，用于建立定量标准曲线。
- **`Wavelength Notes.txt` (可选)**：记录定位光源的标称波长与公差（例如：`R: 622.5nm+-2.5nm`）。若不提供，软件将使用内置默认值。
- **`spd.csv` (可选但强烈推荐)**：标准光源的真实光谱功率分布数据。格式为两列：`Wavelength_nm, Relative_Intensity`。

### 3. 四步法操作指南 (4-Step Wizard Workflow)

运行程序启动图形界面。软件采用严格的**四步线性向导**，请按顺序完成以下操作：

#### 📍 Step 1: 波长定位校准 (Wavelength Calibration)

1. 在左侧面板上传 1-5 张 **Positioning DNG**。
2. (可选) 上传 `Wavelength Notes.txt`。
3. 点击 **"Run Wavelength Calibration"**。
4. **系统行为**：自动识别光谱带横向 ROI，提取 R/G/B 通道一维强度曲线（支持多图平均），利用 B 和 R 的质心拟合线性映射方程 $p = k\lambda + b$，并使用 G 点进行独立验证 (Hold-out Validation)。右侧将展示 RGB 寻峰曲线图。

#### 📍 Step 2: 光谱响应校准 (Spectral Response Calibration)

1. 点击 `Next >` 进入 Step 2。
2. 上传 1-5 张 **SPD DNG** 及 `true_spd.csv`。
3. 点击 **"Run Spectral Response Cal."**。
4. **系统行为**：计算相机 R/G/B 通道的光谱响应修正曲线，消除传感器量子效率 (QE) 差异带来的光谱畸变。右侧展示归一化后的响应度曲线。

#### 📍 Step 3: 样品分析 (Sample Analysis)

1. 点击 `Next >` 进入 Step 3。
2. 在下拉菜单中**选择目标荧光蛋白**（支持 Ypet, EGFP, mCherry, CFP, mTurquoise2）。
3. **成对上传**：分别上传 1-5 张 **Blank DNG** 和 1-5 张 **Sample DNG**。
4. 点击 **"Extract Sample Spectrum"**。
5. **系统行为**：执行严格的物理还原流程（详见下方核心算法），输出扣除背景并校正后的净荧光光谱。右侧图表将高亮显示目标荧光峰的积分区域，底部日志栏输出积分面积与峰值强度。

#### 📍 Step 4: 浓度定量 (Concentration Quantification)

1. 点击 `Next >` 进入 Step 4。
2. 点击 **"+ Add Std"** 添加 2-10 个标准品浓度梯度。
3. 为每个标准品**成对上传**其对应的 Blank 和 Sample DNG（若文件名为纯数字如 `0.5.dng`，软件会自动填入浓度框）。
4. 点击 **"Build Curve & Calculate"**。
5. **系统行为**：自动提取所有标准品的净荧光信号，进行线性回归拟合。右侧将生成**标准曲线散点图**，并用醒目的箭头标注出未知样品的预测浓度。

> 💡 **图表导出**：在任何步骤完成分析后，均可点击右侧顶部的 **"📥 Export Chart"**，将当前图表导出为高分辨率 PNG/PDF/SVG 格式，默认文件名会自动匹配当前步骤的表头。
> 

# 核心物理与算法逻辑 (Core Physics & Algorithms)

FSSA 软件摒弃了简单的“像素-强度”黑盒映射，采用严谨的光学物理模型与统计学算法进行数据处理，确保定量结果的绝对可靠性。以下是软件涉及的核心算法与原理：

#### 1. 图像预处理与物理还原 (Image Preprocessing & Physical Restoration)

- **黑电平扣除 (Black Level Subtraction)**：
传感器在无光照下也会产生暗电流 (Dark Current)。软件首先从 RAW 数据中减去硬件固有的黑电平 (Black Level, 默认 64.0)，消除加性底噪。
- **曝光时间归一化 (Exposure Time Normalization)**：
通过解析 DNG 文件的 EXIF 标签 (Tag 33434) 提取实际曝光时长 $t_{exp}$。所有图像在减去黑电平后，严格除以曝光时间：*原理*：将像素值还原为**绝对光子通量率**。这确保了即使操作员在不同批次拍摄时改变了曝光时间，软件也能保证定量结果的可比性。
    
    $$
    I_{linear} = \frac{Raw - BlackLevel}{t_{exp}}
    $$
    
- **Bayer 去马赛克 (Demosaicing)**：
使用 OpenCV 的 `COLOR_BayerRG2RGB` 算法，将单通道的 RAW 拜耳阵列数据插值还原为完整的 R/G/B 三通道彩色图像。

#### 2. 光谱特征提取与降噪 (Spectral Feature Extraction & Denoising)

- **动态 ROI 自动寻峰 (Dynamic ROI Auto-detection)**：
基于图像总强度的 99.5% 高百分位投影，结合移动平均 (Moving Average) 平滑，自动识别光谱带的横向有效范围 (X-ROI)。该算法完美适配不同手机型号和光栅组装误差，无需硬编码像素坐标。
- **多图平均降噪 (Multi-Shot Averaging)**：
对同一状态上传的多张图像，算法在提取一维光谱轮廓后进行逐像素算术平均。
*原理*：有效抑制**散粒噪声 (Shot Noise)** 和**读出噪声 (Read Noise)**，显著提升低浓度样品的信噪比 (SNR)。
- **质心算法 (Centroid Algorithm)**：
在动态 FWHM (半高全宽) 窗口内，扣除局部基线后，利用加权质心公式计算峰位的精确亚像素坐标：
    
    $$
    y_{centroid} = \frac{\sum (y_i \cdot w_i)}{\sum w_i}, \quad w_i = \max(I_i - I_{baseline}, 0)
    $$
    

#### 3. 波长校准与验证 (Wavelength Calibration & Validation)

- **两点拟合与独立验证 (Two-Point Fit & Hold-out Validation)**：
摒弃“三点拟合看 $R^2$”的自欺欺人做法。软件使用 B (462.5nm) 和 R (622.5nm) 的质心拟合线性映射方程 $p = k\lambda + b$，并将 G (522.5nm) 作为**独立验证点 (Hold-out Validation)**。
*原理*：通过计算 G 点的预测残差并折算为波长误差 (如 $\pm 7.15 nm$)，客观评估系统的色散线性度与装配精度。

#### 4. 光谱响应校准 (Spectral Response Calibration)

- **双重掩码机制 (Dual Masking)**：
在计算相机响应度时，引入物理有效范围掩码 (420-680nm) 和光源可信度掩码 (强度 > 峰值的 5%)。
*原理*：剔除边缘低信噪比区域，防止在光源极弱处进行除法运算时放大噪声。
- **Savitzky-Golay 平滑滤波**：
在置零区域后应用 SG 滤波器 (窗口 21，阶数 2)。
*原理*：在保留光谱峰形特征的前提下，消除高频随机噪声，提取平滑、物理意义明确的相机 R/G/B 响应曲线。

#### 5. 荧光定量分析 (Fluorescence Quantification)

- **Blank 减法与 SPD 除法的严格时序**：
    - **Blank 减法 (消除加性噪声)**：首先执行 $S_{net} = Avg(S_{sample}) - Avg(S_{blank})$，彻底消除暗电流、溶剂拉曼散射及比色杯自发荧光。
    - **SPD 除法 (消除乘性畸变)**：随后执行 $I_{true} = \frac{S_{net}}{R_{camera}(\lambda)}$，利用响应曲线还原真实的荧光发射光谱。
    - *注*：软件严格遵循“先减后除”的物理顺序，避免了将背景噪声放大。
- **荧光积分面积 (Integration Area)**：
在目标荧光蛋白的特征波长范围 (如 Ypet 527nm $\pm$ 10nm) 内，使用梯形法则 (Trapezoidal Rule) 计算净光谱的积分面积，作为荧光强度的核心指标。

#### 6. 浓度预测与统计分析 (Concentration Prediction & Statistics)

- **线性回归拟合 (Linear Regression)**：
使用最小二乘法 (Least Squares) 拟合标准曲线 $I = a \cdot C + b$，并计算决定系数 $R^2$ 评估线性度。
- **误差棒分析 (Error Bar Analysis)**：
对每个浓度梯度的多次拍摄样本，计算其净荧光面积的**样本标准差 (Sample SD, $ddof=1$)**，并在图表中绘制误差棒，直观展示数据的离散程度和重复性。
- **智能局部放大图 (Smart Inset Plot for LOD)**：
自动统计低浓度区域（前 25% 范围）的数据点数量。仅当点数 $\ge 3$ 时启用放大图，并**动态计算包含误差棒上下限的真实 Y 轴极值**。
*原理*：允许 Y 轴显示负值，真实反映背景扣除后低浓度样品的噪声分布，帮助评委直观判断仪器的**检测极限 (Limit of Detection, LOD)**。

# **更新日志和下载链接 (Changelog & Links)**

## v1.3.3 (Current Release) - 2026-09-06

**核心特性：四步法物理工作流重构、曝光归一化与多次采样平均算法**

[Feature] **严谨的四步法物理工作流 (4-Step Workflow)：**将工作流重构为严格的四步线性向导（Step 1: 波长定位 -> Step 2: 光谱响应校准(SPD) -> Step 3: 样品分析(Blank与Sample) -> Step 4: 浓度定量），明确区分了 Blank 减法与 SPD 除法，算法严格遵循“先曝光归一化求平均 -> 执行 Sample - Blank 减法扣除背景 -> 最后除以 SPD 响应曲线进行物理还原”的光学逻辑。

[Feature] **物理级曝光归一化 (Exposure Normalization)：**新增 EXIF 标签 (Tag 33434) 解析功能以提取 DNG 文件的曝光时长，在底层图像读取算法中严格执行“先减去黑电平 (Black Level)，再除以曝光时长”的物理公式，确保不同曝光时间拍摄的图像具有绝对的光强可比性。

[Feature] **多次采样求平均与成对背景扣除 (Multi-Shot & Blank Subtraction)：**新增通用的 `MultiFileSelector` 组件，在波长定位、SPD 校准、样品分析以及浓度定量的所有位置均支持上传 1-5 张 DNG 图像，软件自动在后台提取光谱轮廓并求平均，同时强制要求成对提供 Blank (Buffer) 和 Sample 图像以彻底消除溶剂拉曼与暗电流干扰。

[Feature] **严谨的统计可视化与智能局部放大图 (Error Bars & Smart Inset)**：在浓度定量中引入标准差 (SD) 误差棒分析；新增智能局部放大图逻辑，仅当低浓度区（前 25%）存在 3 个及以上数据点时启用，并**动态计算包含误差棒的真实 Y 轴极值（允许负值显示）**，直观反映仪器的检测极限 (LOD)。

[Feature] **详细信息面板与学术级图表导出 (Details Panel & PDF Export)**：新增 "Details" 按钮，弹出包含 Figure 1-6 全局连续编号的专业分析面板，完整重现 ROI 预览、1D 光谱、拟合验证、响应曲线及标准曲线，并支持一键导出多页 PDF 报告。

[Optimization] **图表编号体系重构 (Stage to Figure)**：废弃旧的 "Stage 01-07" 命名，采用符合学术论文和 iGEM Wiki 规范的 **Figure 1 到 Figure 6** 全局连续编号体系。

[Optimization] **Step 4 浓度定量界面升级 (Scrollable UI & 10 Standards)：**将标准品数量上限从 5 提升至 10，并为 Step 4 的标准品列表区域引入了基于 Canvas 的垂直滚动条与鼠标滚轮事件绑定，确保在添加多个标准品时界面不会溢出，操作丝滑。

[Optimization] **图例与标注优化**：标准曲线图的图例统一移至右上角，并将样品实际蛋白浓度直接动态显示在图例中，使图表更加整洁专业。

分享链接: http://cloud2.cau.edu.cn/share?id=at355prwkta4

## v1.3.2  -  2026-09-05

**核心特性：四步向导式 UI、全量日志追踪与智能定量分析**

[Feature] 引入四步向导式 UI (4-Step Wizard UI)：将左侧操作栏重构为严格的 4 步线性工作流（Step 1: Positioning, Step 2: SPD, Step 3: Sample, Step 4: Concentration），当前步骤以外的控件完全隐藏，彻底消除多校准步骤挤在同一页面的逻辑混淆。

[Feature] 新增图表导出与智能命名功能：右侧可视化区新增导出按钮，支持自由选择路径并导出高分辨率 PNG/PDF/SVG 格式，且默认文件名自动提取自当前图表的带序号表头（如 Step_1_Wavelength_Calibration_Profiles.png）。

[Feature] 新增醒目的浓度定量标注：在 Step 4 的标准曲线图中，使用`matplotlib.annotate`为样品落点添加带箭头与高亮背景框的文本标注，直接显示预测浓度，极大提升数据汇报的直观性。

[Feature] 新增全量操作日志追踪系统：底部 "Analysis Results & Logs" 文本框改为追加模式，完整记录 Step 1 至 Step 4 的所有关键参数（如线性映射方程、ROI 范围、G点验证残差、平滑窗口、拟合方程及 R² 等），并支持自动滚动至最新行。

[Feature] 新增智能浓度预填机制：在 Step 4 上传标准品 DNG 图像时，自动解析不带后缀的文件名，若其为合法浮点数（如 0.5.dng），则瞬间自动填入对应的浓度输入框，大幅减少手动输入操作。

[Optimization] 优化文件选择 UI 交互：将所有文件上传按钮放大至占满整行，并将简写替换为严谨的全称（如 True Spectral Power Distribution CSV），消除歧义；选中的文件名在按钮下方居中并自动换行显示，完美适配长文件名。

[Optimization] 优化光谱曲线可视化映射：在 Step 1 的波长定位校准图表中，强制 R/G/B 三个通道的曲线使用红、绿、蓝三色绘制，与物理传感器通道颜色严格一一对应。

[Bugfix] 修复 NumPy 2.0+ 兼容性崩溃问题。

分享链接: http://cloud2.cau.edu.cn/share?id=acdxg9rwuasw

## v1.3.1  -  2026-09-01

**核心特性：多荧光蛋白支持、浓度定量模块与文件解耦**

[Feature] 重构了交互界面：现在所有的文件上传、执行计算等的功能按钮全部被集成在一个个“功能区”里了。新版本提供 5 个独立的上传按钮。用户可以按照自己习惯的任意顺序上传文件。系统会在后台记录状态，只有在点击“Run”按钮时才会检查当前步骤所必需的前置条件。

[Feature] 智能默认数据回退 (Graceful Degradation)：**Wavelength Notes TXT**：如果用户不上传，系统自动使用内置的默认值（R:622.5, G:522.5, B:462.5）。**True SPD CSV**：如果用户不上传，系统自动生成一条模拟的宽谱光源曲线（高斯分布+底噪）用于响应度校准，确保流程不会因缺少文件而中断。

[Feature] 多荧光蛋白支持 (Multi-Fluorophore)：新增下拉菜单，支持 Ypet, EGFP, mCherry, CFP, mTurquoise2。切换荧光蛋白时，软件会自动调整目标检测波长（如 mCherry 自动切换至 610nm）和提取通道（如 mCherry 自动使用 Red 通道，CFP 使用 Blue 通道）。

[Feature] 荧光浓度定量模块 (Concentration Quantification)：**动态标准品管理：**默认提供 2 行标准品输入，用户可点击 `+ Add Std` 增加至最多 5 行，也可点击 `X` 删除（最少保留 2 行）。**线性推断：**用户输入标准品图像和对应浓度后，点击 `Build Curve & Calculate`。软件会自动提取所有标准品的荧光积分面积，使用 `scipy.stats.linregress` 进行线性回归拟合 (*I*=*a*⋅*C*+*b*)。**结果输出：**自动计算样品的浓度，并在右侧绘制标准曲线散点图、拟合线以及样品落点，同时在左侧文本框输出 *R*2 值和预测浓度。

分享链接: http://cloud2.cau.edu.cn/share?id=aakar9rwuf1v

## v1.3.0: FSSA GUI Version  -  2026-08-31

**核心特性：交互式 GUI 与线性防呆工作流** 

[Feature] 引入轻量级桌面 GUI：基于 Python 原生 `tkinter` 与 `matplotlib` 构建，彻底告别命令行与硬编码文件名。用户可通过系统原生对话框手动导入 DNG、TXT、CSV 文件，实现真正的“开箱即用 (Out-of-the-box)”。

 [Feature] 线性防呆工作流 (Linear Workflow)：将后端算法封装为“波长校准 -> 响应校准 -> 样品检测”的三步线性状态机。前序步骤的结果（如映射方程、响应曲线）在内存中自动传递给下一步，严格规范操作顺序，避免数据错乱。 

[Feature] 定量荧光物理还原：在 Step 3 中，算法自动提取样品 G 通道光谱，扣除背景并除以 Step 2 的光谱响应曲线进行物理还原，最终输出目标波长（如 Ypet ~527nm）的**积分面积 (Area)** 与**峰值强度 (Peak)**，直接反映真实的荧光光照强度。 

[Optimization] 零配置部署：移除了所有对特定文件夹结构和文件命名的依赖，极大降低了非计算机背景生物学家的使用门槛，完美契合 Global South 的野外现场检测场景。

分享链接: http://cloud2.cau.edu.cn/share?id=aa3inmrwupa2

## **v1.2.0: FSSA  - 2026-08-03**

**核心特性：光谱响应校准与完全脱机架构**

[Feature] 引入光谱响应校准 (SPD Calibration)：新增 Stage 07 模块。通过读取标准光源（如 Nichia 白光 LED）的真实 SPD 数据 (`true_spd.csv`)，计算并生成摄像头 R/G/B 通道的光谱响应修正曲线，消除传感器量子效率差异带来的光谱畸变。

[Feature] 完全脱机 (Offline-First) 架构：重构软件底层，移除所有外部网络请求依赖。支持通过 PWA (Service Worker) 或本地 Python 环境运行，实现“数据绝不离端 (Data Never Leaves the Device)”。
[Optimization] 边缘噪声抑制：在 SPD 校准算法中引入 `valid_range` 掩码与 Savitzky-Golay 平滑滤波，有效抑制 420nm 以下和 680nm 以上边缘区域的除零噪声放大问题。

[Bugfix] CSV 解析鲁棒性：修复 `np.genfromtxt` 在读取标准 CSV 表头时的分隔符识别问题，增加数据合理性校验与优雅降级 (Graceful Degradation) 机制。

分享链接： http://cloud2.cau.edu.cn/share?id=aade7nrwuta1

## **v1.1.0 - 2026-07-15**

**核心特性：动态 ROI 与严谨的交叉验证**

[Feature] 动态 ROI 自动寻峰：废弃 v1.0 中硬编码的固定像素搜索框（如 `y: 1200-1900`）。引入基于高百分位投影的自动横向 ROI 检测，以及基于 FWHM 的动态纵向寻峰窗口，完美适配不同手机型号和组装误差。

[Feature] 两点拟合 + 独立验证机制：确立以 B (462.5nm) 和 R (622.5nm) 为训练点拟合线性映射，将 G (522.5nm) 作为独立验证点 (Hold-out Validation) 的严谨校准流程。

[Feature] 多格式波长配置：支持从 `Wavelength Notes.txt` 自动解析波长参数，兼容 `+-`、`±`、`+/-` 等多种公差写法。

[Doc] 完善可视化输出：新增 Stage 01-06 全套调试图表，直观展示自动 ROI、一维强度曲线、局部峰放大图及残差分析。

分享链接: http://cloud2.cau.edu.cn/share?id=aa3inmrwupa2

## v1.0.0 - 2026-06-01

[Feature] 初始版本发布：实现基础的 DNG RAW 数据读取、黑电平扣除、Bayer 去马赛克。

[Feature] 基础波长映射：实现线性波长-像素映射算法：$p(\lambda) = k\lambda + b$
