# CSV Data Cleaner Introduction

### **CSV 数据清洗与光谱重采样工具 (CSV Data Cleaner)简介**

可以将 WebPlotDigitizer 从光谱图表中提取的原始数据清洗并转换为标准格式。

其他软件项目的介绍：**Fungal Sentinel Spectral Analyzer (FSSA) Devblog** 

---

#### **由来**

在构建低成本荧光检测仪的光谱校准模型时，我们需要标准光源（如 Nichia 白光 LED）的真实光谱功率分布（SPD）数据。由于直接获取仪器原始数据受限，我们使用 WebPlotDigitizer 从制造商的官方数据手册中提取了光谱曲线。然而，手动提取的原始数据存在波长未排序、包含负值（基线漂移）、采样点不均匀以及带有提取噪声等问题，无法直接用于严谨的光谱响应校准计算。为此，我们开发了 `csv_data_cleaner.py` 脚本，将粗糙的提取数据转化为高精度的标准物理模型。

#### **核心功能**

1. **自动纠错与清洗**：自动截断负值（修正基线漂移），按波长严格升序排序，并去除重复的采样点。
2. **数据归一化**：将光谱的最大相对强度统一缩放为 1.0，消除绝对亮度差异带来的影响。
3. **物理级保形重采样 (PCHIP Interpolation)**：采用保形分段三次 Hermite 插值（PCHIP）算法，将不均匀的散点重采样为 350nm 至 750nm、步长为 1nm 的标准等距网格。该算法能完美保留光谱的峰谷特征，避免传统插值产生的虚假过冲或振荡。
4. **可视化验证**：自动生成清洗前后的对比图表，直观验证数据修复与重采样的效果。

#### **操作方法**

1. 使用 WebPlotDigitizer 从光源数据手册（PDF/图片）中提取光谱数据，并导出为 CSV 文件。
2. 将导出的文件重命名为 `Default Dataset.csv`，并将其与 `csv_data_cleaner.py` 脚本放置在同一个文件夹中。
3. 运行 Python 脚本（需安装 `pandas`, `numpy`, `matplotlib`, `scipy`）。程序将自动读取数据、执行清洗与重采样流程。
4. 运行结束后，文件夹内将生成标准化的 `true_spd.csv`（可直接供校准主程序调用）以及验证对比图 `spectrum_cleaning_validation.png`。

#### 下载链接

分享链接: http://cloud2.cau.edu.cn/share?id=ac42wrrwu6sp
