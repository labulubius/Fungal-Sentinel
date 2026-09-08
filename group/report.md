## What's new? Updates
在 Fungal-Sentinel v1.0 基础上集成了 FSSA 模块（未发布）。
#### Kotlin 的 FSSA 模块相对于 python 文件做了什么修改？
- 读取方式的改进，Python 用 tifffile 和 OpenCV 读取已经保存的 DNG，Android 直接从 Camera2 获取传感器原始数据，不生成完整的 RGB 图，直接从 Bayer 数据计算 RGB 的一维光谱。
- 黑电平处理：python 默认使用固定黑电平，Kotlin 版优先读取不同 Bayer 位置的独立黑电平，适配不同手机和不同 CFA 像素处理
- 核心算法不变，`SciPy` 库的算法手动实现（减少依赖）。

## 流程图

| Order | 操作过程        | 数据 Pipeline                             | 技术栈                                       | 具体实现                                                                                                |
| ----- | ----------- | --------------------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 1     | 启动应用并初始化相机  | 相机权限 → 获取 Camera ID → 读取设备能力 → 初始化预览    | Kotlin、Android Camera2、Jetpack Compose    | `MainActivity.kt` 申请相机权限，检测设备是否支持 RAW、手动曝光和手动对焦，并通过 `TextureView` 显示实时画面                            |
| 2     | 调整拍摄参数      | 用户输入参数→ 相机硬件                            | Camera2、Compose Slider/Switch             | `CameraControlSettings.kt` 保存曝光、ISO、焦距、白平衡和降噪参数；`MainActivity.kt` 将参数写入相机请求                         |
| 3     | 拍摄并保存 RAW   | DNG 文件                                  | Camera2、ImageReader、DngCreator、MediaStore | 拍摄最大尺寸 RAW，将图像和拍摄元数据配对，并保存到 `Pictures/FungalSentinel`                                               |
|       |             |                                         |                                           |                                                                                                     |
| 4     | 提取一维 RGB 光谱 | RAW Bayer → 黑电平扣除 → ROI 检测 → R/G/B 一维曲线 | Kotlin、Camera2 RAW、Bayer/CFA 解析           | `RawProfileExtractor.kt` 自动定位发光区域，按 Bayer 排列拆分颜色通道，并对 ROI 内像素按行求平均                                  |
| 5     | 波长定位校准      | R/G/B 曲线 → 平滑 → 峰值检测 → R/B 两点拟合 → G 点验证 | Kotlin 数值算法、移动平均、质心法、线性拟合                 | `SpectralAlgorithms.calibrateWavelength()` 使用 B 462.5 nm 和 R 622.5 nm 建立像素—波长映射，用 G 522.5 nm 计算校准误差 |
| 6     | 相机光谱响应校准    | 标准光源 RAW + SPD → 波长插值 → 测量值/SPD → 平滑归一化 | Kotlin、CSV 解析、线性插值、移动平均                   | 导入两列 SPD CSV；未导入时使用模拟 SPD，在 420–680 nm 范围生成相机 R/G/B 响应曲线                                            |
| 7     | 分析未知样品      | 样品 RAW → RGB 曲线 → 背景扣除 → 响应校正 → 波段积分    | Kotlin、百分位背景估计、插值、梯形积分                    | 根据选择的荧光蛋白使用对应通道，在目标波长附近计算校正后的积分面积和峰值强度                                                              |
| 8     | 采集标准品       | 标准品浓度 + 标准品 RAW → 光谱分析 → 浓度/面积数据点       | Kotlin、Compose 表单、光谱分析算法                  | 用户输入标准品浓度并拍摄 2–5 个标准品，生成 `StandardMeasurement` 列表                                                   |
| 9     | 计算样品浓度      | 标准品数据点 → 线性回归 → 标准曲线 → 样品浓度             | Kotlin、最小二乘线性回归                           | `calculateConcentration()` 拟合 `I = aC + b`，计算 R²，并由样品积分面积反推出浓度                                      |
| 10    | 展示与记录结果     | 校准结果/光谱/浓度 → UI State → 曲线与日志           | Jetpack Compose、Canvas、Material 3         | `FssaPanel.kt` 显示 RGB 曲线、响应曲线、样品光谱、浓度结果及最近的分析日志                                                     |
| 11    |             |                                         |                                           |                                                                                                     |
## What to do next? 
- 整合 wyh part
- 整理软件逻辑
- 加入 config、log 功能
- 优化软件架构
- 调试 美工
## Architecture

```
  Fungal-Sentinel/                                                                                                
  ├── app/                         # Android 应用主模块                                                           
  │   ├── src/                                                                                                    
  │   │   ├── main/                # 正式应用代码与资源                                                           
  │   │   ├── test/                # JVM 单元测试                                                                 
  │   │   └── androidTest/         # Android 设备测试                                                             
  │   ├── build.gradle.kts         # app 模块构建配置                                                             
  │   ├── build/                   # Gradle 构建产物                                                              
  │   └── release/                 # 已生成的发布 APK                                                             
  │                                                                                                               
  ├── gradle/                      # Gradle Wrapper 与版本目录                                                    
  ├── docs/                        # 项目说明和验证文档                                                           
  ├── dist/                        # 对外分发的 APK                                                               
  ├── updates/                     # 辅助程序、更新日志和历史资料                                                 
  ├── keystore/                    # APK 发布签名文件                                                             
  ├── build.gradle.kts             # 项目级构建配置                                                               
  ├── settings.gradle.kts          # Gradle 项目与模块声明                                                        
  ├── gradle.properties            # Gradle 全局配置                                                              
  ├── gradlew / gradlew.bat        # Gradle Wrapper 启动脚本                                                      
  └── README.md                    # 项目介绍、安装和构建说明
```

| 文件                         | 类别        | 主要作用                                                                      |
| -------------------------- | --------- | ------------------------------------------------------------------------- |
| `MainActivity.kt`          | 应用入口、相机控制 | 应用主入口；负责相机权限、Camera2 预览与拍摄、手动参数控制、DNG 保存，以及串联完整的光谱分析流程。                   |
| `CameraControlSettings.kt` | 相机控制      | 定义相机控制参数、可用范围和设备支持能力，例如曝光时间、ISO、焦距、白平衡、降噪和锐化。                             |
| `FssaPanel.kt`             | FSSA      | 使用 Jetpack Compose 构建 FSSA 四步分析界面，并绘制 RGB 光谱、相机响应和样本光谱曲线。                 |
| `RawProfileExtractor.kt`   | FSSA      | 直接读取 `RAW_SENSOR` Bayer 数据，扣除黑电平、识别 RGB 通道、寻找发光 ROI，并生成一维 RGB 光谱曲线和拍摄元数据。 |
| `SpectralAlgorithms.kt`    | FSSA      | 实现核心计算，包括波长校准、SPD 响应校准、样本光谱修正、积分、浓度回归、插值、移动平均及 SPD CSV 解析。                |
| `SpectralModels.kt`        | FSSA      | 定义分析流程使用的数据模型和状态，例如光谱曲线、峰值、校准结果、荧光物信息、浓度结果及 `FssaUiState`。                |
| `AppConstants.kt`          |           | 保存应用包名、DNG 文件保存目录等全局常量。                                                   |
