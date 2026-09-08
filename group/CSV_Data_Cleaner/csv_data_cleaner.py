import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from scipy.interpolate import PchipInterpolator

# 1. 读取 WebPlotDigitizer 导出的原始 CSV (假设没有表头)
try:
    df = pd.read_csv('Default Dataset.csv', header=None, names=['wl', 'intensity'])
except FileNotFoundError:
    print("❌ 找不到 'Default Dataset.csv'，请确保文件在当前目录下。")
    exit()

print(f"📥 成功读取原始数据，共 {len(df)} 个数据点。")

# 2. 数据清洗：处理负值 (截断为 0，相当于扣除基线底噪)
df['intensity'] = df['intensity'].clip(lower=0)

# 3. 数据清洗：按波长严格从小到大排序
df = df.sort_values('wl')

# 4. 数据清洗：去除可能重复的波长点
df = df.drop_duplicates(subset=['wl'], keep='first')

# 5. 归一化：将最大强度设为 1.0
max_int = df['intensity'].max()
df['intensity'] = df['intensity'] / max_int
print(f"✨ 数据已排序、去负值并归一化 (峰值波长: {df.loc[df['intensity'].idxmax(), 'wl']:.1f} nm)。")

# 6. 物理重采样 (PCHIP 插值)
# 生成 350nm 到 750nm，步长为 1nm 的标准等距网格
target_wl = np.arange(350, 751, 1)

# 使用 PchipInterpolator (保形插值，不会产生虚假的波峰波谷)
interp_func = PchipInterpolator(df['wl'].values, df['intensity'].values, extrapolate=False)
target_int = interp_func(target_wl)

# 将插值范围外 (如 <408nm 的区域) 的 NaN 安全地填充为 0
target_int = np.nan_to_num(target_int, nan=0.0)
target_int = np.clip(target_int, 0, 1)

# 7. 导出完美的标准 CSV
cleaned_df = pd.DataFrame({
    'Wavelength_nm': target_wl,
    'Relative_Intensity': np.round(target_int, 5)
})
cleaned_df.to_csv('true_spd.csv', index=False)
print(f"✅ 清洗完成！完美的标准 SPD 数据已保存至 'true_spd.csv' (共 {len(cleaned_df)} 行)。")

# ==========================================
# 📊 可视化对比 (验证清洗效果)
# ==========================================
plt.figure(figsize=(10, 5))

# 绘制原始散点 (带瑕疵的)
plt.scatter(df['wl'], df['intensity'], s=15, color='red', alpha=0.6, label='Raw Digitized Points (Sorted & Normalized)', zorder=3)

# 绘制清洗后的平滑曲线
plt.plot(target_wl, target_int, 'k-', linewidth=2, label='Cleaned & Resampled Spectrum (PCHIP)', zorder=2)

plt.axvline(450, color='blue', linestyle=':', alpha=0.5, label='Blue Chip Region')
plt.axvline(550, color='orange', linestyle=':', alpha=0.5, label='Phosphor Region')

plt.title("White LED Spectrum: Raw Digitized vs Cleaned Physical Model", fontsize=13)
plt.xlabel("Wavelength (nm)", fontsize=11)
plt.ylabel("Relative Intensity (Normalized)", fontsize=11)
plt.xlim(380, 750)
plt.ylim(-0.05, 1.05)
plt.legend(loc='upper right', fontsize=9)
plt.grid(True, linestyle='--', alpha=0.6)
plt.tight_layout()
plt.savefig('spectrum_cleaning_validation.png', dpi=150)
print("📊 验证图已保存至 'spectrum_cleaning_validation.png'")