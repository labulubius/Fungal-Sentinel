"""
Fungal Sentinel Spectral Analyzer (FSSA) - GUI Version v1.3.3 (Final Polish)
iGEM 2026 Software Track
Features: Fixed Inset Plot Y-axis (allows negative), Renamed Stage to Figure 1-6.
"""
from __future__ import annotations
import re
import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional, Tuple, List

import cv2
import numpy as np
import tifffile as tiff
from scipy.interpolate import interp1d
from scipy.signal import savgol_filter
from scipy.stats import linregress

import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
from matplotlib.figure import Figure
from matplotlib.patches import Rectangle
from mpl_toolkits.axes_grid1.inset_locator import inset_axes
from matplotlib.backends.backend_pdf import PdfPages

# 兼容 NumPy 2.0+
if hasattr(np, 'trapezoid'):
    np_trapz = np.trapezoid
else:
    np_trapz = np.trapz

# =============================================================================
# 荧光蛋白数据库
# =============================================================================
FLUOROPHORES = {
    "Ypet (Venus variant)": {"peak_wl": 527, "channel": "green", "fwhm": 20, "color": "green"},
    "EGFP": {"peak_wl": 509, "channel": "green", "fwhm": 20, "color": "green"},
    "mCherry": {"peak_wl": 610, "channel": "red", "fwhm": 25, "color": "red"},
    "CFP": {"peak_wl": 475, "channel": "blue", "fwhm": 20, "color": "blue"},
    "mTurquoise2": {"peak_wl": 482, "channel": "blue", "fwhm": 20, "color": "cyan"}
}

# =============================================================================
# Data classes & Core Algorithms
# =============================================================================
@dataclass
class KnownWavelength:
    name: str; wavelength_nm: float; tolerance_nm: float; channel_index: int; channel_name: str

@dataclass
class PeakResult:
    name: str; wavelength_nm: float; channel_name: str; y_centroid: float; y_max: int; baseline: float
    lo: int; hi: int; peak_height: float

@dataclass
class LinearMapping:
    slope_k: float; intercept_b: float
    def wavelength_to_pixel(self, wl: float) -> float: return self.slope_k * wl + self.intercept_b
    def pixel_to_wavelength(self, px: float) -> float: return (px - self.intercept_b) / self.slope_k if self.slope_k != 0 else float('nan')

@dataclass
class TwoPointValidation:
    train_names: Tuple[str, str]; validate_name: str
    slope_k: float; intercept_b: float
    predicted_y: float; measured_y: float; residual_px: float; wavelength_error_nm: float

def get_exposure_time(path: str | Path) -> float:
    try:
        with tiff.TiffFile(str(path)) as tif:
            page = tif.pages[0]
            if 33434 in page.tags:
                val = page.tags[33434].value
                if isinstance(val, tuple) and len(val) == 2:
                    return float(val[0]) / float(val[1]) if val[1] != 0 else 1.0
                return float(val)
    except Exception: pass
    return 1.0

def read_dng_linear_rgb(path: str | Path, black_level: float = 64.0) -> Tuple[np.ndarray, float]:
    path = Path(path)
    raw = tiff.imread(str(path)).astype(np.float32)
    raw = np.clip(raw - black_level, 0, None)
    exp_time = get_exposure_time(path)
    if exp_time <= 0: exp_time = 1.0
    raw = raw / exp_time  
    max_val = np.max(raw)
    scale = 65535.0 / max_val if max_val > 0 else 1.0
    raw16 = np.clip(raw * scale, 0, 65535).astype(np.uint16)
    rgb = cv2.cvtColor(raw16, cv2.COLOR_BayerRG2RGB).astype(np.float32) / scale
    return rgb, exp_time

def normalize_for_display(rgb: np.ndarray, percentile: float = 99.7) -> np.ndarray:
    upper = float(np.percentile(rgb, percentile))
    if upper <= 0: upper = 1.0
    return np.clip(rgb / upper, 0, 1)

def moving_average(x: np.ndarray, window: int = 9) -> np.ndarray:
    if window <= 1: return x.astype(np.float64)
    if window % 2 == 0: window += 1
    padded = np.pad(x.astype(np.float64), pad_width=window//2, mode="edge")
    return np.convolve(padded, np.ones(window)/window, mode="valid")

def auto_detect_x_roi(rgb: np.ndarray) -> Tuple[int, int]:
    width = rgb.shape[1]
    x_profile = np.percentile(rgb.sum(axis=2), 99.5, axis=0)
    x_smooth = moving_average(x_profile, window=max(11, width // 300 * 2 + 1))
    base, peak = float(np.percentile(x_smooth, 20)), float(np.max(x_smooth))
    if peak <= base: return int(width * 0.35), int(width * 0.65)
    mask = x_smooth > (base + 0.35 * (peak - base))
    segments, start = [], None
    for i, val in enumerate(mask):
        if val and start is None: start = i
        if (not val or i == len(mask) - 1) and start is not None:
            segments.append((start, i if not val else i + 1)); start = None
    if not segments: return int(width * 0.35), int(width * 0.65)
    x0, x1 = max(segments, key=lambda s: s[1] - s[0])
    return int(max(0, x0 - 80)), int(min(width, x1 + 80))

def make_channel_profiles(rgb: np.ndarray, x_roi: Tuple[int, int]) -> Dict[str, np.ndarray]:
    x0, x1 = x_roi
    return {"red": rgb[:, x0:x1, 0].mean(axis=1), "green": rgb[:, x0:x1, 1].mean(axis=1), "blue": rgb[:, x0:x1, 2].mean(axis=1)}

def get_average_profiles(dng_paths: List[str], x_roi: Tuple[int, int]) -> Dict[str, np.ndarray]:
    if not dng_paths: return {"red": np.zeros(1), "green": np.zeros(1), "blue": np.zeros(1)}
    sum_profiles = {"red": None, "green": None, "blue": None}
    for path in dng_paths:
        rgb, _ = read_dng_linear_rgb(path)
        profs = make_channel_profiles(rgb, x_roi)
        for k in sum_profiles:
            if sum_profiles[k] is None: sum_profiles[k] = profs[k].copy()
            else: sum_profiles[k] += profs[k]
    n = len(dng_paths)
    return {k: v / n for k, v in sum_profiles.items()}

def find_peak_auto(profile: np.ndarray, known: KnownWavelength) -> PeakResult:
    n = len(profile)
    smooth = moving_average(profile, 11)
    valid_lo, valid_hi = 50, n - 50
    baseline = float(np.percentile(smooth[valid_lo:valid_hi], 10))
    corrected = np.clip(smooth - baseline, 0, None)
    y_max = int(np.argmax(corrected[valid_lo:valid_hi]) + valid_lo)
    peak_height = float(corrected[y_max])
    if peak_height <= 0: raise RuntimeError(f"No positive peak for {known.name}")
    half = 0.5 * peak_height
    left, right = y_max, y_max
    while left > valid_lo and corrected[left] > half: left -= 1
    while right < valid_hi - 1 and corrected[right] > half: right += 1
    lo, hi = max(valid_lo, left - 45), min(valid_hi, right + 46)
    ys = np.arange(lo, hi, dtype=np.float64)
    weights = np.clip(profile[lo:hi] - baseline, 0, None)
    if weights.sum() <= 0: raise RuntimeError(f"Centroid failed for {known.name}")
    y_centroid = float((ys * weights).sum() / weights.sum())
    return PeakResult(known.name, known.wavelength_nm, known.channel_name, y_centroid, y_max, baseline, lo, hi, peak_height)

def parse_known_wavelengths(file_path: Optional[str | Path]) -> Dict[str, KnownWavelength]:
    defaults = {"R": KnownWavelength("R", 622.5, 2.5, 0, "red"), "G": KnownWavelength("G", 522.5, 2.5, 1, "green"), "B": KnownWavelength("B", 462.5, 2.5, 2, "blue")}
    if file_path is None: return defaults
    text = Path(file_path).read_text("utf-8", errors="ignore")
    pattern = re.compile(r"^\s*([RGB])\s*:\s*([0-9]+\.?[0-9]*)\s*nm\s*[+\-±/]+\s*([0-9]+\.?[0-9]*)\s*nm", re.IGNORECASE | re.MULTILINE)
    found = {}
    for match in pattern.finditer(text):
        c = match.group(1).upper()
        found[c] = KnownWavelength(c, float(match.group(2)), float(match.group(3)), {"R":0,"G":1,"B":2}[c], {"R":"red","G":"green","B":"blue"}[c])
    for c in ("R", "G", "B"):
        if c not in found: found[c] = defaults[c]
    return found

def load_true_spd_data(file_path: Optional[str | Path]) -> Tuple[np.ndarray, np.ndarray]:
    if file_path is None: return None, None
    data = np.genfromtxt(file_path, delimiter=',', skip_header=1, encoding='utf-8')
    data = data[~np.isnan(data).any(axis=1)]
    if data.ndim == 2 and data.shape[1] >= 2: return data[:, 0], data[:, 1]
    raise ValueError("SPD CSV format error.")

class SpectralResponseCalibrator:
    def __init__(self, mapping: LinearMapping, valid_range=(420, 680), smooth_window=21):
        self.mapping = mapping; self.valid_range = valid_range; self.smooth_window = smooth_window
        self.wavelengths = self.response_R = self.response_G = self.response_B = None

    def calibrate(self, pixel_coords, measured_rgb, true_spd_wl, true_spd_val):
        self.wavelengths = self.mapping.pixel_to_wavelength(pixel_coords)
        true_spd_aligned = interp1d(true_spd_wl, true_spd_val, bounds_error=False, fill_value=0.0)(self.wavelengths)
        max_spd = np.max(true_spd_aligned)
        final_mask = (self.wavelengths >= self.valid_range[0]) & (self.wavelengths <= self.valid_range[1]) & (true_spd_aligned > max_spd * 0.05)
        eps = 1e-6
        raw = measured_rgb / (true_spd_aligned[:, None] + eps)
        raw[~final_mask] = 0
        if len(raw) > self.smooth_window:
            resp = np.column_stack([savgol_filter(raw[:, i], self.smooth_window, 2, mode='nearest') for i in range(3)])
        else: resp = raw
        resp[~final_mask] = 0
        self.response_R, self.response_G, self.response_B = resp[:, 0], resp[:, 1], resp[:, 2]
        for r in [self.response_R, self.response_G, self.response_B]:
            mx = np.max(r)
            if mx > 0: r /= mx
        return self.response_R, self.response_G, self.response_B

# =============================================================================
# 通用多文件选择组件
# =============================================================================
class MultiFileSelector(ttk.Frame):
    def __init__(self, master, title, max_files=5, **kwargs):
        super().__init__(master, **kwargs)
        self.max_files = max_files
        self.files = []
        ttk.Label(self, text=title, font=("Arial", 9, "bold")).pack(anchor=tk.W)
        btn_frame = ttk.Frame(self)
        btn_frame.pack(fill=tk.X, pady=2)
        ttk.Button(btn_frame, text=f"Add (Max {max_files})", width=14, command=self.add_files).pack(side=tk.LEFT)
        ttk.Button(btn_frame, text="Clear", width=6, command=self.clear_files).pack(side=tk.LEFT, padx=5)
        self.listbox = tk.Listbox(self, height=3, font=("Consolas", 8), selectmode=tk.EXTENDED)
        self.listbox.pack(fill=tk.X, expand=True)
        scrollbar = ttk.Scrollbar(self, orient=tk.HORIZONTAL, command=self.listbox.xview)
        self.listbox.config(xscrollcommand=scrollbar.set)
        scrollbar.pack(fill=tk.X)

    def add_files(self):
        paths = filedialog.askopenfilenames(title="Select DNG Files", filetypes=[("DNG", "*.dng")])
        if not paths: return
        remaining = self.max_files - len(self.files)
        if remaining <= 0:
            messagebox.showinfo("Limit Reached", f"Maximum {self.max_files} files allowed."); return
        for p in paths[:remaining]:
            self.files.append(p)
            self.listbox.insert(tk.END, Path(p).name)
            
    def clear_files(self):
        self.files = []
        self.listbox.delete(0, tk.END)

    def get_files(self) -> List[str]:
        return self.files

# =============================================================================
# GUI Application (v1.3.3 Final Polish)
# =============================================================================
class FSSA_GUI:
    def __init__(self, root):
        self.root = root
        self.root.title("Fungal Sentinel Spectral Analyzer (FSSA) v1.3.3 - iGEM 2026")
        self.root.geometry("1250x800")
        
        self.txt_file = None
        self.csv_file = None
        self.mapping = None
        self.x_roi = None
        self.calibrator = None
        self.current_fluorophore = "Ypet (Venus variant)"
        self.current_page = 1
        self.current_chart_filename = "FSSA_Chart"
        
        self.cache_step1 = {}
        self.cache_step2 = {}
        self.cache_step3 = {}
        self.cache_step4 = {}
        
        self.setup_ui()
        
    def setup_ui(self):
        left_frame = ttk.Frame(self.root, width=340)
        left_frame.pack(side=tk.LEFT, fill=tk.Y)
        left_frame.pack_propagate(False)
        
        ttk.Label(left_frame, text="FSSA v1.3.3", font=("Arial", 16, "bold")).pack(pady=15)
        
        self.content_frame = ttk.Frame(left_frame)
        self.content_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        self.page1 = ttk.Frame(self.content_frame)
        self.page2 = ttk.Frame(self.content_frame)
        self.page3 = ttk.Frame(self.content_frame)
        self.page4 = ttk.Frame(self.content_frame)
        
        self._setup_page1_positioning()
        self._setup_page2_spd()
        self._setup_page3_sample()
        self._setup_page4_concentration()
        
        nav_frame = ttk.Frame(left_frame)
        nav_frame.pack(fill=tk.X, padx=10, pady=15)
        self.btn_prev = ttk.Button(nav_frame, text="< Prev", command=self.prev_page)
        self.btn_prev.pack(side=tk.LEFT)
        self.lbl_page_num = ttk.Label(nav_frame, text="Step 1 / 4", font=("Arial", 11, "bold"))
        self.lbl_page_num.pack(side=tk.LEFT, expand=True)
        self.btn_next = ttk.Button(nav_frame, text="Next >", command=self.next_page)
        self.btn_next.pack(side=tk.RIGHT)
        
        self.show_page(1)

        right_frame = ttk.Frame(self.root)
        right_frame.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True)
        
        toolbar = ttk.Frame(right_frame)
        toolbar.pack(fill=tk.X, padx=10, pady=5)
        ttk.Label(toolbar, text="Real-time Visualization", font=("Arial", 12, "bold")).pack(side=tk.LEFT)
        ttk.Button(toolbar, text="📋 Details", command=self.show_details_window).pack(side=tk.RIGHT, padx=2)
        ttk.Button(toolbar, text="📥 Export Chart", command=self.export_chart).pack(side=tk.RIGHT)
        
        self.fig = Figure(figsize=(8, 5), dpi=100)
        self.ax = self.fig.add_subplot(111)
        self.ax.set_title("Welcome to FSSA v1.3.3\nPlease proceed with Step 1: Positioning.")
        self.ax.axis('off')
        self.canvas_plot = FigureCanvasTkAgg(self.fig, master=right_frame)
        self.canvas_plot.get_tk_widget().pack(fill=tk.BOTH, expand=True, padx=10)
        
        res_frame = ttk.LabelFrame(right_frame, text="Analysis Results & Logs", padding=5)
        res_frame.pack(fill=tk.X, padx=10, pady=(0, 10))
        self.txt_result = scrolledtext.ScrolledText(res_frame, height=8, font=("Consolas", 10))
        self.txt_result.pack(fill=tk.X)

    def _setup_page1_positioning(self):
        f = self.page1
        ttk.Label(f, text="Step 1: Wavelength Calibration", font=("Arial", 12, "bold"), foreground="blue").pack(anchor=tk.W, pady=(0,10))
        self.pos_selector = MultiFileSelector(f, "Positioning DNG (1-5 images)", max_files=5)
        self.pos_selector.pack(fill=tk.X, pady=5)
        ttk.Label(f, text="Wavelength Notes TXT (Optional):", font=("Arial", 9, "bold")).pack(anchor=tk.W, pady=(10,0))
        txt_frame = ttk.Frame(f); txt_frame.pack(fill=tk.X)
        ttk.Button(txt_frame, text="Select TXT", command=self.select_txt).pack(side=tk.LEFT)
        self.lbl_txt = ttk.Label(txt_frame, text="Default", foreground="gray"); self.lbl_txt.pack(side=tk.LEFT, padx=5)
        ttk.Button(f, text="Run Wavelength Calibration", command=self.step1_calibrate).pack(fill=tk.X, pady=15)
        self.lbl_status1 = ttk.Label(f, text="Status: Ready", foreground="gray", wraplength=300); self.lbl_status1.pack(anchor=tk.W)

    def _setup_page2_spd(self):
        f = self.page2
        ttk.Label(f, text="Step 2: Spectral Response Cal. (SPD)", font=("Arial", 12, "bold"), foreground="green").pack(anchor=tk.W, pady=(0,10))
        self.spd_selector = MultiFileSelector(f, "SPD Calibration DNG (1-5 images)", max_files=5)
        self.spd_selector.pack(fill=tk.X, pady=5)
        ttk.Label(f, text="True SPD CSV (Optional):", font=("Arial", 9, "bold")).pack(anchor=tk.W, pady=(10,0))
        csv_frame = ttk.Frame(f); csv_frame.pack(fill=tk.X)
        ttk.Button(csv_frame, text="Select CSV", command=self.select_csv).pack(side=tk.LEFT)
        self.lbl_csv = ttk.Label(csv_frame, text="None (Skip SPD Cal.)", foreground="gray"); self.lbl_csv.pack(side=tk.LEFT, padx=5)
        ttk.Button(f, text="Run Spectral Response Cal.", command=self.step2_calibrate).pack(fill=tk.X, pady=15)
        self.lbl_status2 = ttk.Label(f, text="Status: Ready", foreground="gray", wraplength=300); self.lbl_status2.pack(anchor=tk.W)

    def _setup_page3_sample(self):
        f = self.page3
        ttk.Label(f, text="Step 3: Sample Analysis", font=("Arial", 12, "bold"), foreground="purple").pack(anchor=tk.W, pady=(0,10))
        ttk.Label(f, text="Select Fluorophore:", font=("Arial", 9, "bold")).pack(anchor=tk.W)
        self.fluor_combo = ttk.Combobox(f, values=list(FLUOROPHORES.keys()), state="readonly", width=25)
        self.fluor_combo.set(self.current_fluorophore); self.fluor_combo.pack(pady=2, anchor=tk.W)
        self.fluor_combo.bind("<<ComboboxSelected>>", self.on_fluorophore_change)
        ttk.Separator(f, orient=tk.HORIZONTAL).pack(fill=tk.X, pady=10)
        self.blank_selector = MultiFileSelector(f, "Blank / Background (1-5 images)", max_files=5)
        self.blank_selector.pack(fill=tk.X, pady=5)
        self.sample_selector = MultiFileSelector(f, "Sample DNG (1-5 images)", max_files=5)
        self.sample_selector.pack(fill=tk.X, pady=5)
        ttk.Button(f, text="Extract Sample Spectrum", command=self.step3_analyze).pack(fill=tk.X, pady=15)
        self.lbl_status3 = ttk.Label(f, text="Status: Ready", foreground="gray", wraplength=300); self.lbl_status3.pack(anchor=tk.W)

    def _setup_page4_concentration(self):
        f = self.page4
        ttk.Label(f, text="Step 4: Concentration Quantification", font=("Arial", 12, "bold"), foreground="red").pack(anchor=tk.W, pady=(0,10))
        canvas_frame = ttk.Frame(f); canvas_frame.pack(fill=tk.BOTH, expand=True, pady=5)
        self.std_canvas = tk.Canvas(canvas_frame, highlightthickness=0)
        scrollbar = ttk.Scrollbar(canvas_frame, orient="vertical", command=self.std_canvas.yview)
        self.std_rows_frame = ttk.Frame(self.std_canvas)
        self.std_rows_frame.bind("<Configure>", lambda e: self.std_canvas.configure(scrollregion=self.std_canvas.bbox("all")))
        self.std_canvas.create_window((0, 0), window=self.std_rows_frame, anchor="nw")
        self.std_canvas.configure(yscrollcommand=scrollbar.set)
        self.std_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        def _on_mousewheel(event):
            if event.num == 4: self.std_canvas.yview_scroll(-1, "units")
            elif event.num == 5: self.std_canvas.yview_scroll(1, "units")
            else: self.std_canvas.yview_scroll(int(-1*(event.delta/120)), "units")
        self.std_canvas.bind_all("<MouseWheel>", _on_mousewheel)
        self.std_canvas.bind_all("<Button-4>", _on_mousewheel)
        self.std_canvas.bind_all("<Button-5>", _on_mousewheel)
        self.std_rows = []
        btn_frame = ttk.Frame(f); btn_frame.pack(fill=tk.X, pady=5)
        self.btn_add_std = ttk.Button(btn_frame, text="+ Add Std", command=self.add_std_row); self.btn_add_std.pack(side=tk.LEFT, padx=2)
        self.btn_calc_conc = ttk.Button(btn_frame, text="Build Curve & Calculate", command=self.calculate_concentration); self.btn_calc_conc.pack(side=tk.LEFT, padx=2)
        self.add_std_row(); self.add_std_row()
        self.lbl_status4 = ttk.Label(f, text="Status: Ready", foreground="gray", wraplength=300); self.lbl_status4.pack(anchor=tk.W, pady=5)

    def show_page(self, page_num):
        self.current_page = page_num
        for p in [self.page1, self.page2, self.page3, self.page4]: p.pack_forget()
        getattr(self, f"page{page_num}").pack(fill=tk.BOTH, expand=True)
        self.lbl_page_num.config(text=f"Step {page_num} / 4")
        self.btn_prev.config(state="normal" if page_num > 1 else "disabled")
        self.btn_next.config(state="normal" if page_num < 4 else "disabled")
    def prev_page(self): self.show_page(self.current_page - 1)
    def next_page(self): self.show_page(self.current_page + 1)
    def select_txt(self):
        path = filedialog.askopenfilename(filetypes=[("Text", "*.txt")])
        if path: self.txt_file = path; self.lbl_txt.config(text=Path(path).name, foreground="black")
    def select_csv(self):
        path = filedialog.askopenfilename(filetypes=[("CSV", "*.csv")])
        if path: self.csv_file = path; self.lbl_csv.config(text=Path(path).name, foreground="black")
    def on_fluorophore_change(self, event): self.current_fluorophore = self.fluor_combo.get()
    def log_status(self, step, msg, color="black"):
        getattr(self, f"lbl_status{step}").config(text=f"Status: {msg}", foreground=color); self.root.update()
    def set_chart_title(self, title):
        self.ax.set_title(title, fontsize=13, fontweight='bold')
        self.current_chart_filename = re.sub(r'[^\w\s-]', '', title).strip().replace(' ', '_')
    def export_chart(self):
        file_path = filedialog.asksaveasfilename(title="Export Chart", initialfile=f"{self.current_chart_filename}.png", defaultextension=".png", filetypes=[("PNG", "*.png"), ("PDF", "*.pdf"), ("SVG", "*.svg")])
        if file_path:
            try: self.fig.savefig(file_path, dpi=300, bbox_inches='tight'); messagebox.showinfo("Success", f"Saved to:\n{file_path}")
            except Exception as e: messagebox.showerror("Error", str(e))

    def calculate_net_signal_single(self, avg_blank_rgb, sample_path, fluor_config):
        ch_name = fluor_config['channel']
        ch_idx = {"red":0, "green":1, "blue":2}[ch_name]
        rgb_s, _ = read_dng_linear_rgb(sample_path)
        prof_s = make_channel_profiles(rgb_s, self.x_roi)
        sample_rgb = np.column_stack([prof_s[c] for c in ["red", "green", "blue"]])
        net_rgb = np.clip(sample_rgb - avg_blank_rgb, 0, None)
        y_pixels = np.arange(len(net_rgb))
        wavelengths = self.mapping.pixel_to_wavelength(y_pixels)
        if self.calibrator:
            resp_map = {"red": self.calibrator.response_R, "green": self.calibrator.response_G, "blue": self.calibrator.response_B}
            resp_interp = np.interp(wavelengths, self.calibrator.wavelengths, resp_map[ch_name])
            resp_interp = np.where(resp_interp > 0.05, resp_interp, 0.05)
            true_intensity = net_rgb[:, ch_idx] / resp_interp
        else:
            true_intensity = net_rgb[:, ch_idx]
        target_wl, fwhm = fluor_config['peak_wl'], fluor_config['fwhm']
        mask = (wavelengths >= target_wl - fwhm/2) & (wavelengths <= target_wl + fwhm/2)
        area = np_trapz(true_intensity[mask], wavelengths[mask]) if np.any(mask) else 0
        return area, wavelengths, true_intensity, mask

    def get_net_areas_batch(self, blank_paths, sample_paths, fluor_config):
        avg_blank_rgb = np.column_stack([get_average_profiles(blank_paths, self.x_roi)[c] for c in ["red", "green", "blue"]])
        areas = []
        last_wls, last_int, last_mask = None, None, None
        for s_path in sample_paths:
            area, wls, intensity, mask = self.calculate_net_signal_single(avg_blank_rgb, s_path, fluor_config)
            areas.append(area)
            last_wls, last_int, last_mask = wls, intensity, mask
        return areas, last_wls, last_int, last_mask

    def step1_calibrate(self):
        pos_files = self.pos_selector.get_files()
        if not pos_files: messagebox.showwarning("Warning", "Please add Positioning DNG(s)!"); return
        self.log_status(1, "Calibrating...", "orange")
        try:
            knowns = parse_known_wavelengths(self.txt_file)
            rgb_first, exp_first = read_dng_linear_rgb(pos_files[0])
            self.x_roi = auto_detect_x_roi(rgb_first)
            avg_profiles = get_average_profiles(pos_files, self.x_roi)
            peaks = [find_peak_auto(avg_profiles[knowns[k].channel_name], knowns[k]) for k in ["R", "G", "B"]]
            p1, p2, hold = next(p for p in peaks if p.name=="B"), next(p for p in peaks if p.name=="R"), next(p for p in peaks if p.name=="G")
            k = (p2.y_centroid - p1.y_centroid) / (p2.wavelength_nm - p1.wavelength_nm)
            b = p1.y_centroid - k * p1.wavelength_nm
            self.mapping = LinearMapping(k, b)
            pred_y = k * hold.wavelength_nm + b
            res_px = hold.y_centroid - pred_y
            wl_err = res_px / abs(k)
            validation = TwoPointValidation(("B", "R"), "G", k, b, pred_y, hold.y_centroid, res_px, wl_err)
            
            self.log_status(1, f"Done! Exp: {exp_first:.4f}s | G-err: {wl_err:+.2f} nm", "green")
            log_text = f"\n=== Step 1: Wavelength Calibration ===\nExposure Time Normalized: {exp_first:.4f}s\nMapping: p = {k:.4f} * λ + {b:.2f}\nG-point Error: {wl_err:+.2f} nm\n"
            self.txt_result.insert(tk.END, log_text); self.txt_result.see(tk.END)
            
            self.cache_step1 = {"rgb": rgb_first, "x_roi": self.x_roi, "profiles": avg_profiles, "peaks": peaks, "mapping": self.mapping, "validation": validation, "exp_time": exp_first, "wl_err": wl_err}
            
            self.ax.clear()
            colors = {"red": "red", "green": "green", "blue": "blue"}
            for name, prof in avg_profiles.items(): self.ax.plot(prof, color=colors[name], label=f"{name.capitalize()} (Avg)", linewidth=1.5)
            for p in peaks: self.ax.axvline(p.y_centroid, linestyle="--", color="black", label=f"{p.name}")
            self.set_chart_title("Step 1: Wavelength Calibration Profiles")
            self.ax.set_xlabel("Y Pixel"); self.ax.set_ylabel("Intensity"); self.ax.legend(fontsize=8); self.fig.tight_layout(); self.canvas_plot.draw()
        except Exception as e: self.log_status(1, f"Error: {e}", "red"); messagebox.showerror("Error", str(e))

    def step2_calibrate(self):
        if not self.mapping: messagebox.showwarning("Warning", "Please complete Step 1 first!"); return
        spd_files = self.spd_selector.get_files()
        if not spd_files: messagebox.showwarning("Warning", "Please add SPD DNG(s)!"); return
        self.log_status(2, "Calibrating Response...", "orange")
        try:
            avg_spd_rgb_dict = get_average_profiles(spd_files, self.x_roi)
            y_pixels = np.arange(len(avg_spd_rgb_dict["red"]), dtype=np.float64)
            measured_rgb = np.column_stack((avg_spd_rgb_dict["red"], avg_spd_rgb_dict["green"], avg_spd_rgb_dict["blue"]))
            true_wl, true_val = load_true_spd_data(getattr(self, 'csv_file', None))
            if true_wl is None: messagebox.showwarning("Warning", "True SPD CSV is required for Step 2!"); return
            self.calibrator = SpectralResponseCalibrator(self.mapping)
            self.calibrator.calibrate(y_pixels, measured_rgb, true_wl, true_val)
            self.log_status(2, "Response curves generated.", "green")
            log_text = f"\n=== Step 2: Spectral Response Calibration ===\nAveraged {len(spd_files)} SPD images.\nStatus: R/G/B response curves successfully generated.\n"
            self.txt_result.insert(tk.END, log_text); self.txt_result.see(tk.END)
            
            self.cache_step2 = {"wavelengths": self.calibrator.wavelengths, "R": self.calibrator.response_R, "G": self.calibrator.response_G, "B": self.calibrator.response_B}
            
            self.ax.clear()
            self.ax.plot(self.calibrator.wavelengths, self.calibrator.response_R, 'r-', label='R')
            self.ax.plot(self.calibrator.wavelengths, self.calibrator.response_G, 'g-', label='G')
            self.ax.plot(self.calibrator.wavelengths, self.calibrator.response_B, 'b-', label='B')
            self.set_chart_title("Step 2: Spectral Response Curves")
            self.ax.set_xlabel("Wavelength (nm)"); self.ax.set_ylabel("Sensitivity"); self.ax.legend(); self.fig.tight_layout(); self.canvas_plot.draw()
        except Exception as e: self.log_status(2, f"Error: {e}", "red"); messagebox.showerror("Error", str(e))

    def step3_analyze(self):
        if not self.mapping: messagebox.showwarning("Warning", "Please complete Step 1 first!"); return
        blank_files = self.blank_selector.get_files()
        sample_files = self.sample_selector.get_files()
        if not sample_files or not blank_files: messagebox.showwarning("Warning", "Please add Blank & Sample DNG(s)!"); return
        self.log_status(3, "Extracting...", "orange")
        try:
            fluor = FLUOROPHORES[self.current_fluorophore]
            areas, wls, intensity, mask = self.get_net_areas_batch(blank_files, sample_files, fluor)
            mean_area = np.mean(areas)
            peak = np.max(intensity[mask]) if np.any(mask) else 0
            
            log_text = f"\n=== Step 3: Sample Analysis ===\nFluorophore: {self.current_fluorophore}\nNet Area (Mean of {len(areas)} shots): {mean_area:.2f} | Peak: {peak:.2f}\n"
            self.txt_result.insert(tk.END, log_text); self.txt_result.see(tk.END)
            self.log_status(3, "Sample analyzed.", "green")
            
            self.cache_step3 = {"wls": wls, "intensity": intensity, "mask": mask, "fluor": fluor, "mean_area": mean_area, "peak": peak}
            
            self.ax.clear()
            self.ax.plot(wls, intensity, 'k-', label='Net Spectrum (Sample - Blank / SPD)')
            self.ax.fill_between(wls, intensity, where=mask, color=fluor['color'], alpha=0.4, label='Integration')
            self.ax.axvline(fluor['peak_wl'], color='red', linestyle='--', label='Target Peak')
            self.set_chart_title("Step 3: Net Fluorescence Spectrum")
            self.ax.set_xlabel("Wavelength (nm)"); self.ax.set_ylabel("True Intensity"); self.ax.set_xlim(450, 650); self.ax.legend(); self.fig.tight_layout(); self.canvas_plot.draw()
        except Exception as e: self.log_status(3, f"Error: {e}", "red"); messagebox.showerror("Error", str(e))

    def add_std_row(self):
        if len(self.std_rows) >= 10: messagebox.showinfo("Info", "Max 10 standards."); return
        row_frame = ttk.LabelFrame(self.std_rows_frame, text=f"Standard {len(self.std_rows)+1}", padding=5)
        row_frame.pack(fill=tk.X, pady=5)
        top_frame = ttk.Frame(row_frame); top_frame.pack(fill=tk.X)
        ttk.Label(top_frame, text="Conc:").pack(side=tk.LEFT)
        conc_var = tk.StringVar(value="0.0")
        ttk.Entry(top_frame, textvariable=conc_var, width=6).pack(side=tk.LEFT, padx=5)
        ttk.Button(top_frame, text="X", width=3, command=lambda r=row_frame: self.remove_std_row(r)).pack(side=tk.RIGHT)
        mini_blank = MultiFileSelector(row_frame, "Blank (1-5)", max_files=5); mini_blank.pack(fill=tk.X, pady=2)
        mini_sample = MultiFileSelector(row_frame, "Sample (1-5)", max_files=5); mini_sample.pack(fill=tk.X, pady=2)
        self.std_rows.append({"frame": row_frame, "conc_var": conc_var, "blank": mini_blank, "sample": mini_sample})
        self.update_std_buttons()
    def remove_std_row(self, row_frame):
        if len(self.std_rows) <= 2: messagebox.showinfo("Info", "Min 2 required."); return
        for i, row in enumerate(self.std_rows):
            if row["frame"] == row_frame: row_frame.destroy(); self.std_rows.pop(i); break
        self.update_std_buttons()
    def update_std_buttons(self):
        self.btn_add_std.config(state="normal" if len(self.std_rows) < 10 else "disabled")

    def calculate_concentration(self):
        if not self.mapping: messagebox.showwarning("Warning", "Please complete Step 1 & 2 first!"); return
        std_data = []
        for row in self.std_rows:
            try: conc = float(row["conc_var"].get())
            except ValueError: messagebox.showerror("Error", "Invalid conc."); return
            b_files = row["blank"].get_files(); s_files = row["sample"].get_files()
            if not b_files or not s_files: messagebox.showerror("Error", "Missing Blank/Sample in standards."); return
            std_data.append({"conc": conc, "blank": b_files, "sample": s_files})
            
        self.log_status(4, "Calculating...", "orange")
        try:
            fluor = FLUOROPHORES[self.current_fluorophore]
            concentrations = []
            means = []
            stds = []
            
            for data in std_data:
                areas, _, _, _ = self.get_net_areas_batch(data["blank"], data["sample"], fluor)
                concentrations.append(data["conc"])
                means.append(np.mean(areas))
                stds.append(np.std(areas, ddof=1) if len(areas) > 1 else 0)
                
            res = linregress(concentrations, means)
            slope, intercept, r_value = res.slope, res.intercept, res.rvalue
            
            sample_areas, _, _, _ = self.get_net_areas_batch(self.blank_selector.get_files(), self.sample_selector.get_files(), fluor)
            sample_mean_area = np.mean(sample_areas)
            sample_conc = (sample_mean_area - intercept) / slope if slope != 0 else 0
            
            log_text = f"\n=== Step 4: Concentration Quantification ===\nCurve: I = {slope:.2f}*C + {intercept:.2f} (R²={r_value**2:.4f})\nSample Net Area: {sample_mean_area:.2f}\nPREDICTED CONCENTRATION: {sample_conc:.3f} (a.u.)\n"
            self.txt_result.insert(tk.END, log_text); self.txt_result.see(tk.END)
            self.log_status(4, "Done.", "green")
            
            self.cache_step4 = {"concentrations": concentrations, "means": means, "stds": stds, "slope": slope, "intercept": intercept, "r_value": r_value, "sample_conc": sample_conc, "sample_mean_area": sample_mean_area}
            
            self.ax.clear()
            self.ax.errorbar(concentrations, means, yerr=stds, fmt='o', color='blue', capsize=5, label='Standards (Mean±SD)', zorder=5, ecolor='lightblue')
            x_line = np.linspace(min(concentrations)*0.8, max(concentrations)*1.2, 100)
            self.ax.plot(x_line, slope * x_line + intercept, 'r--', linewidth=2, label=f'Linear Fit (R²={r_value**2:.3f})')
            self.ax.scatter([sample_conc], [sample_mean_area], color='red', marker='*', s=150, label=f'Sample (Conc: {sample_conc:.3f})', zorder=6, edgecolors='darkred')
            self.ax.legend(loc='upper right', fontsize=10, framealpha=0.9)
            
            # 【修复】智能 Inset 逻辑：允许负值显示
            max_conc = max(concentrations) if concentrations else 1.0
            x_thresh = max_conc * 0.25
            points_in_region = sum(1 for c in concentrations if c <= x_thresh)
            if sample_conc <= x_thresh: points_in_region += 1
            
            if points_in_region >= 3:
                axins = inset_axes(self.ax, width="40%", height="40%", loc='lower left', borderpad=1.5)
                axins.errorbar(concentrations, means, yerr=stds, fmt='o', color='blue', capsize=3, markersize=4, ecolor='lightblue')
                axins.plot(x_line, slope * x_line + intercept, 'r--', linewidth=1.5)
                axins.scatter([sample_conc], [sample_mean_area], color='red', marker='*', s=50)
                axins.set_xlim(0, x_thresh * 1.2)
                
                # 动态计算 Y 轴范围（包含误差棒，允许负值）
                y_min_inset = float('inf')
                y_max_inset = float('-inf')
                for c, m, s in zip(concentrations, means, stds):
                    if c <= x_thresh:
                        y_min_inset = min(y_min_inset, m - s)
                        y_max_inset = max(y_max_inset, m + s)
                if sample_conc <= x_thresh:
                    y_min_inset = min(y_min_inset, sample_mean_area)
                    y_max_inset = max(y_max_inset, sample_mean_area)
                
                if y_min_inset == float('inf'):
                    y_min_inset, y_max_inset = 0, 1
                    
                y_range = y_max_inset - y_min_inset
                # 向下扩展 15% 边距，确保 0 轴或负值清晰可见
                axins.set_ylim(y_min_inset - 0.15 * y_range, y_max_inset + 0.15 * y_range)
                
                axins.set_title("Zoom-in: Low Conc. Region", fontsize=8)
                axins.tick_params(labelsize=7)
                axins.grid(True, linestyle=':', alpha=0.5)
            
            self.set_chart_title("Step 4: Standard Curve & Concentration")
            self.ax.set_xlabel("Concentration (a.u.)", fontsize=11); self.ax.set_ylabel("Net Fluorescence Area", fontsize=11)
            self.ax.grid(True, linestyle=':', alpha=0.7)
            self.fig.tight_layout(); self.canvas_plot.draw()
            
        except Exception as e: self.log_status(4, f"Error: {e}", "red"); messagebox.showerror("Error", str(e))

    # ================= 详细信息面板 (Figure 1-6 命名体系) =================
    def show_details_window(self):
        if not self.mapping:
            messagebox.showinfo("Info", "Please complete at least Step 1 to view details."); return
            
        win = tk.Toplevel(self.root)
        win.title("FSSA Detailed Analysis Report (Figure 1-6)")
        win.geometry("1000x800")
        
        notebook = ttk.Notebook(win)
        notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        # --- Tab 1: Step 1 Details (Figure 1, 2, 3) ---
        tab1 = ttk.Frame(notebook)
        notebook.add(tab1, text="Step 1: Wavelength Cal.")
        if self.cache_step1:
            fig1 = Figure(figsize=(10, 8), dpi=100)
            
            # Figure 1: ROI Preview
            ax01 = fig1.add_subplot(3, 1, 1)
            rgb = self.cache_step1["rgb"]
            x0, x1 = self.cache_step1["x_roi"]
            ax01.imshow(normalize_for_display(rgb))
            ax01.add_patch(Rectangle((x0, 0), x1 - x0, rgb.shape[0], fill=False, linewidth=2, edgecolor='yellow', label='Auto X ROI'))
            for p in self.cache_step1["peaks"]:
                ax01.axhline(p.y_centroid, linestyle="--", linewidth=1.5, label=f"{p.name} ({p.wavelength_nm}nm)")
            ax01.set_title("Figure 1: Positioning DNG Preview with Auto ROI"); ax01.legend(fontsize=7, loc='upper right')
            
            # Figure 2: 1D Profiles
            ax02 = fig1.add_subplot(3, 1, 2)
            ys = np.arange(len(self.cache_step1["profiles"]["red"]))
            colors = {"red": "red", "green": "green", "blue": "blue"}
            for name, prof in self.cache_step1["profiles"].items():
                ax02.plot(ys, prof, color=colors[name], label=f"{name.capitalize()} Channel")
            for p in self.cache_step1["peaks"]:
                ax02.axvline(p.y_centroid, linestyle="--", color="black", label=f"{p.name} Centroid")
                ax02.axvspan(p.lo, p.hi, alpha=0.1, color=colors[p.channel_name])
            ax02.set_title("Figure 2: Automatic 1D Profiles and Dynamic Peak Windows"); ax02.set_xlabel("Y Pixel"); ax02.legend(fontsize=7, ncol=3)
            
            # Figure 3: Fit Validation
            ax05 = fig1.add_subplot(3, 1, 3)
            v = self.cache_step1["validation"]
            lam_grid = np.linspace(440, 640, 200)
            pred_grid = v.slope_k * lam_grid + v.intercept_b
            ax05.plot(lam_grid, pred_grid, 'k-', label=f"Fit: p = {v.slope_k:.4f}λ + {v.intercept_b:.2f}")
            ax05.scatter([462.5, 622.5], [self.cache_step1["peaks"][2].y_centroid, self.cache_step1["peaks"][0].y_centroid], color='blue', label="Train (B, R)", zorder=5)
            ax05.scatter([522.5], [self.cache_step1["peaks"][1].y_centroid], marker='x', s=100, color='red', label="Validate (G)", zorder=5)
            ax05.plot([522.5, 522.5], [v.predicted_y, self.cache_step1["peaks"][1].y_centroid], 'r--', label=f"Error: {v.wavelength_error_nm:+.2f} nm")
            ax05.set_title("Figure 3: Two-point Fit (B+R) with Third-point Validation (G)"); ax05.set_xlabel("Wavelength (nm)"); ax05.set_ylabel("Y Pixel"); ax05.invert_yaxis(); ax05.legend(fontsize=8)
            
            fig1.tight_layout()
            canvas1 = FigureCanvasTkAgg(fig1, master=tab1)
            canvas1.get_tk_widget().pack(fill=tk.BOTH, expand=True)

        # --- Tab 2: Step 2 Details (Figure 4) ---
        tab2 = ttk.Frame(notebook)
        notebook.add(tab2, text="Step 2: SPD Response")
        if self.cache_step2:
            fig2 = Figure(figsize=(8, 5), dpi=100)
            ax2 = fig2.add_subplot(111)
            ax2.plot(self.cache_step2["wavelengths"], self.cache_step2["R"], 'r-', label='R Response', linewidth=2)
            ax2.plot(self.cache_step2["wavelengths"], self.cache_step2["G"], 'g-', label='G Response', linewidth=2)
            ax2.plot(self.cache_step2["wavelengths"], self.cache_step2["B"], 'b-', label='B Response', linewidth=2)
            ax2.axvline(527, color='orange', linestyle=':', label='Ypet (~527nm)')
            ax2.set_title("Figure 4: Camera Spectral Response Curves (Normalized)"); ax2.set_xlabel("Wavelength (nm)"); ax2.set_ylabel("Sensitivity"); ax2.legend()
            canvas2 = FigureCanvasTkAgg(fig2, master=tab2)
            canvas2.get_tk_widget().pack(fill=tk.BOTH, expand=True)

        # --- Tab 3: Step 3 Details (Figure 5) ---
        tab3 = ttk.Frame(notebook)
        notebook.add(tab3, text="Step 3: Sample Spectrum")
        if self.cache_step3:
            fig3 = Figure(figsize=(8, 5), dpi=100)
            ax3 = fig3.add_subplot(111)
            fluor = self.cache_step3["fluor"]
            ax3.plot(self.cache_step3["wls"], self.cache_step3["intensity"], 'k-', label='Net Spectrum')
            ax3.fill_between(self.cache_step3["wls"], self.cache_step3["intensity"], where=self.cache_step3["mask"], color=fluor['color'], alpha=0.4, label='Integration')
            ax3.axvline(fluor['peak_wl'], color='red', linestyle='--', label='Target Peak')
            ax3.set_title("Figure 5: Background-Subtracted & SPD-Corrected Spectrum"); ax3.set_xlabel("Wavelength (nm)"); ax3.set_ylabel("True Intensity"); ax3.legend()
            canvas3 = FigureCanvasTkAgg(fig3, master=tab3)
            canvas3.get_tk_widget().pack(fill=tk.BOTH, expand=True)

        # --- Tab 4: Step 4 Details (Figure 6) ---
        tab4 = ttk.Frame(notebook)
        notebook.add(tab4, text="Step 4: Concentration")
        if self.cache_step4:
            fig4 = Figure(figsize=(8, 5), dpi=100)
            ax4 = fig4.add_subplot(111)
            c4 = self.cache_step4
            ax4.errorbar(c4["concentrations"], c4["means"], yerr=c4["stds"], fmt='o', color='blue', capsize=5, label='Standards', ecolor='lightblue')
            x_line = np.linspace(min(c4["concentrations"])*0.8, max(c4["concentrations"])*1.2, 100)
            ax4.plot(x_line, c4["slope"] * x_line + c4["intercept"], 'r--', label=f'Fit (R²={c4["r_value"]**2:.3f})')
            ax4.scatter([c4["sample_conc"]], [c4["sample_mean_area"]], color='red', marker='*', s=150, label=f'Sample (Conc: {c4["sample_conc"]:.3f})')
            ax4.set_title("Figure 6: Standard Curve & Concentration Quantification"); ax4.set_xlabel("Concentration (a.u.)"); ax4.set_ylabel("Net Area"); ax4.legend(loc='upper right'); ax4.grid(True, linestyle=':', alpha=0.7)
            canvas4 = FigureCanvasTkAgg(fig4, master=tab4)
            canvas4.get_tk_widget().pack(fill=tk.BOTH, expand=True)

        btn_frame = ttk.Frame(win)
        btn_frame.pack(fill=tk.X, padx=10, pady=10)
        figs_to_export = [f for f in [fig1 if self.cache_step1 else None, fig2 if self.cache_step2 else None, fig3 if self.cache_step3 else None, fig4 if self.cache_step4 else None] if f is not None]
        ttk.Button(btn_frame, text="📥 Export Full Report (PDF)", command=lambda: self.export_pdf_report(figs_to_export)).pack(side=tk.LEFT)
        ttk.Button(btn_frame, text="Close", command=win.destroy).pack(side=tk.RIGHT)

    def export_pdf_report(self, figures):
        if not figures: return
        file_path = filedialog.asksaveasfilename(title="Export PDF Report", defaultextension=".pdf", filetypes=[("PDF Document", "*.pdf")])
        if file_path:
            try:
                with PdfPages(file_path) as pdf:
                    for fig in figures:
                        pdf.savefig(fig, bbox_inches='tight')
                messagebox.showinfo("Success", f"Full Report saved to:\n{file_path}")
            except Exception as e:
                messagebox.showerror("Error", str(e))

if __name__ == "__main__":
    root = tk.Tk()
    style = ttk.Style(); style.theme_use('clam') 
    app = FSSA_GUI(root)
    root.mainloop()