# Fungal Sentinel 软件审查问题清单

> 记录日期：2026-09-11
>
> 状态：待处理
>
> 审查范围：Android 主程序、Camera2/RAW 流程、光谱算法、构建配置与自动化测试

本文记录当前代码审查中发现的问题，作为后续版本规划和验收依据。这里重点记录尚未解决的问题，不替代 `docs/next-version-improvement-plan.md` 中已有的真机验收计划。

## 当前基线

- 软件定位：离线 Android Camera2 RAW 荧光光谱分析工具。
- 当前版本：v1.3.6（versionCode 8，已移植 Python FSSA v1.3.4 算法流程）。
- `./gradlew testDebugUnitTest lintDebug`：执行成功。
- 当前总体判断：主要功能已经具备，属于功能型 Beta；在用于正式实验定量或广泛发布前，需要优先加强科学质量控制、异步状态一致性和相机异常处理。

---

## P0：影响科学结果可信度

### 1. 波长校准失败后仍可继续分析

**状态：已修复。** G 误差按 `≤2.5 nm / ≤10 nm / >10 nm` 分类；FAILED 会保留结果供诊断，但 Step 2 被阻止，UI 要求调整光路后重拍。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/SpectralModels.kt:48-62`
- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:470-500`

**现状：**

`WavelengthCalibration.qualityMessage` 已根据绿色峰验证误差生成 `PASS`、`WARNING` 或 `FAILED`，但分析流程没有使用这个结果作为门槛。即使验证误差很大，校准结果仍会保存，并允许继续进行响应校准、样品分析和浓度计算。

**风险：**

错误的峰识别或不可靠的波长映射可能一路传递，最终产生看起来正常但实际无效的定量结果。

**建议：**

- `FAILED` 时不保存校准结果，并明确要求用户重拍；
- `WARNING` 时显示醒目提示，并要求用户确认后才能继续；
- 将误差阈值集中定义，避免模型和 UI 各自硬编码；
- 增加 PASS、WARNING、FAILED 三类单元测试和流程测试。

**验收标准：**

- 误差大于失败阈值时 Step 2 不可用；
- UI 显示误差值、质量等级和重拍建议；
- 不合格校准不会保留为有效的 `wavelengthCalibration`。

### 2. 已计算饱和率，但没有过曝拦截

**状态：已修复，阈值待真机标定复核。** `<0.1%` 通过、`0.1%–1%` 警告、`≥1%` 拒绝进入批次；DNG 仍照常保存，UI 显示最近一帧饱和率。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/RawProfileExtractor.kt:77-98`
- `app/src/main/java/org/fungalsentinel/app/SpectralModels.kt:21-31`
- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:470-539`

**现状：**

RAW 提取结果包含 `saturatedFraction`，但定位源、SPD、样品和标准品拍摄都没有检查或展示该值。

**风险：**

削顶后的峰位置、响应曲线和积分面积会失真，导致后续浓度结果不可靠。

**建议：**

- 定义统一的过曝警告和拒绝阈值；
- 对每次拍摄显示饱和像素比例；
- 超过拒绝阈值时保存 DNG，但拒绝将数据写入分析状态；
- 后续补充信号过低和动态范围不足检查。

**验收标准：**

- 明显过曝帧不会生成有效校准、样品或标准品结果；
- 用户能够看到拒绝原因和降低曝光/ISO的建议；
- 自动化测试覆盖正常、临界和严重饱和场景。

### 3. 浓度回归质量门槛不足

**状态：部分修复。** 非有限值和负标准浓度已在拍摄前拒绝；两浓度水平、非正斜率、低 R²、负预测和范围外预测会显示质量警告。为保持 Python v1.3.4 的 OLS 输出兼容，目前仍保留带警告的原始预测值，不将其删除。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/SpectralAlgorithms.kt:115-145`
- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:521-532`

**现状：**

当前回归主要检查标准品数量、浓度是否不同以及斜率是否非零。负浓度输入、负斜率和很低的 `R²` 仍可能得到浓度结果。`toDoubleOrNull()` 也会接受 `NaN` 和无穷值，直到后续阶段才失败。

**风险：**

软件可能将没有合理线性关系的数据展示为成功定量结果。

**建议：**

- 标准浓度必须有限且非负；
- 拍摄按钮启用前完成输入校验；
- 根据业务模型检查斜率方向；
- 设置可配置的最低 `R²`；
- 对范围外预测、负预测和低质量拟合区分“警告”与“不可定量”；
- 优先建议至少三个标准品。

**验收标准：**

- 非有限值和负浓度无法触发标准品拍摄；
- 负斜率或低于质量阈值的曲线不会显示为成功定量；
- UI 明确显示失败原因、拟合公式、`R²` 和标定范围。

### 4. RAW 后台处理可能覆盖用户的新状态

**状态：已随 Python v1.3.4 多帧移植修复。** 拍摄开始时保存不可变 `FssaUiState` 快照；处理期间禁止步骤、相机参数及批次操作，提交时继续校验 capture token 和 purpose。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:398-548`
- `app/src/main/java/org/fungalsentinel/app/CameraController.kt:303-323`

**现状：**

RAW 回调在相机后台线程中处理。`processAnalysisCapture()` 读取当时的整个 `fssaState`，生成 `next`，之后再切回 UI 线程覆盖状态。提交条件只校验 capture token 和拍摄用途；处理期间修改波长、SPD、荧光团或部分相机参数，不一定会使 token 失效。

**风险：**

旧拍摄结果可能覆盖用户刚修改的输入，甚至恢复本应被清除的校准和下游结果。

**建议：**

- 拍摄开始时冻结不可变的分析输入快照；
- 为所有会影响分析结果的状态增加统一 revision；
- 结果提交时同时校验 capture token、purpose 和 revision；
- 或将所有业务状态转换串行化到主线程/ViewModel 中。

**验收标准：**

- RAW 处理期间修改参数后，旧结果不得写入新状态；
- 参数改变后应清除的结果不会被后台任务恢复；
- 有自动化测试稳定复现并验证该场景。

### 5. 内置 SPD 缺少完整溯源信息

**位置：**

- `app/src/main/res/raw/true_spd.csv`
- `README.md`
- `docs/agent-handoff.md`

**现状：**

应用默认加载内置 `true_spd.csv`，但尚未在程序和文档中明确记录其对应光源型号、测量设备、测量日期、光学条件和数据版本。

**风险：**

用户可能将只适用于特定光源的 SPD 当成通用标准数据，导致响应校准和定量结果失去可比性。

**建议：**

- 为内置 SPD 建立元数据记录；
- UI 显示来源、适用光源和版本；
- 来源未确认前标记为“演示数据，不用于正式定量”；
- 导出结果时一并记录 SPD 文件摘要和来源。

**验收标准：**

- 能够明确回答内置 SPD 由谁、用什么设备、对什么光源、在什么条件下测得；
- 使用内置 SPD 时，用户能在分析页看到适用范围；
- 实验报告中能够追踪 SPD 版本。

---

## P1：稳定性与发布安全

### 6. 单次 RAW 拍摄缺少同步异常保护

**状态：代码已修复，待真机复核。** 已增加同步提交异常保护、按 capture token 清理 pending 状态、重复拍摄拒绝，以及关闭后的 ImageReader 回调保护。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/CameraController.kt:227-286`

**现状：**

`createCaptureRequest()`、参数设置及 `session.capture()` 外层没有异常处理。相机断连、Activity 切后台或 session 正在关闭时，可能抛出 `CameraAccessException` 或 `IllegalStateException`。失败前已经写入的 pending capture 状态也可能无法清理。

**建议：**

- 捕获 Camera2 和 session 生命周期异常；
- 在所有同步失败路径统一清理 pending image/result/token；
- 通过 `onError` 返回可理解、可重试的错误；
- 增加快速切后台、关闭相机同时拍摄的测试。

**验收标准：**

- 相机断开或 session 关闭期间点击拍摄不会导致应用崩溃；
- 失败后按钮和 pending 状态能够恢复，可再次拍摄。

### 7. DNG 保存和 RAW 分析阻塞相机线程

**状态：代码已修复，待高分辨率 RAW 真机复核。** 已增加独立单线程 RAW 处理器和显式 `RawCapture` 租约；相机线程只转交任务，旧 ImageReader 会等待租约释放后再关闭。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:398-548`
- `app/src/main/java/org/fungalsentinel/app/RawProfileExtractor.kt:40-49`
- `app/src/main/java/org/fungalsentinel/app/CameraController.kt:87-93`

**现状：**

DNG 写盘和完整 RAW 分析在 Camera `HandlerThread` 回调中同步执行。高分辨率 RAW 的 ROI 搜索和排序可能耗时较长，而相机线程停止时只等待一秒。

**风险：**

可能造成预览卡顿、回调积压、暂停/恢复延迟，以及旧、新相机线程短暂并存。

**建议：**

- Camera 线程只负责接收并转交捕获数据；
- DNG 保存和分析放到独立、可取消的工作线程或协程调度器；
- 明确 Image 生命周期和任务所有权；
- Activity 销毁时取消或等待未完成任务。

**验收标准：**

- 高分辨率 RAW 分析期间相机生命周期操作不被长时间阻塞；
- 连续暂停/恢复后不存在遗留相机线程；
- 重复拍摄不会积累未关闭的 Image。

### 8. Release 构建会静默回退到 debug 签名

**状态：已修复。** Release 始终使用独立 release signing config；配置缺失或不完整时，AGP 的 `validateSigningRelease` 会因明确命名的缺失 keystore 路径而让所有实际 Release 打包路径失败，不再生成 debug 签名或未签名的误发布 APK。

**位置：**

- `app/build.gradle.kts:44-46`

**现状：**

缺少 `keystore.properties` 时，Release 构建自动使用 debug signing config。

**风险：**

可能误发布 debug 签名的 APK，导致后续正式签名版本无法覆盖升级。

**建议：**

- 正式 Release 构建缺少有效密钥时明确失败；
- 如开发阶段确实需要无正式密钥的 release-like 构建，应建立名称明确的独立 build type；
- 在发布流程中校验 APK 签名证书摘要。

**验收标准：**

- 缺少发布密钥时 `assembleRelease` 失败并给出明确说明；
- 发布产物的证书摘要与记录一致。

### 9. 摄像头选择策略不保证支持 RAW

**状态：代码已修复，待多摄像头真机复核。** 当前按“后置 + RAW + 手动传感器、后置 + RAW、其他后置、其他 RAW”的顺序选择；无摄像头或初始化失败时显示稳定错误页面。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/CameraController.kt:33-40`

**现状：**

当前选择系统返回的第一个后置摄像头，没有优先检查 RAW 和手动控制能力；没有后置摄像头时会在控制器初始化阶段抛错。

**建议：**

- 枚举摄像头并按“后置 + RAW + MANUAL_SENSOR”等能力排序；
- 允许用户在多个可用摄像头之间选择；
- 无兼容摄像头时显示稳定的兼容性页面，而不是初始化异常。

**验收标准：**

- 多后摄设备优先选择具备 RAW 的摄像头；
- 无后摄或无 RAW 设备仍能正常进入应用并看到能力说明。

### 10. Bayer 缺失通道与真实零值混用

**状态：已修复。** R/G/B 行分别维护有效性掩码，只对没有对应 Bayer 样本的行插值，真实 `0.0` 信号会原样保留。

**位置：**

- `app/src/main/java/org/fungalsentinel/app/RawProfileExtractor.kt:125-127`

**现状：**

数值 `0.0` 同时表示某 Bayer 行没有该通道，以及黑电平扣除后的真实零信号；随后插值逻辑可能把真实零值当成缺失值覆盖。

**建议：**

使用独立的有效性掩码或根据 CFA 布局确定缺失位置，不用信号值本身表示“缺失”。

**验收标准：**

- 真实零信号不会被邻行数据覆盖；
- 对各类 CFA 排列增加提取测试。

---

## P2：产品体验与可维护性

### 11. 分析会话未持久化

**位置：**

- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:33-47`
- `app/src/main/java/org/fungalsentinel/app/SpectralModels.kt:143-183`

**现状：**

校准、标准品、样品和结果主要保存在 Activity/Compose 状态中。配置变化或进程被系统回收后，可能丢失整套实验进度。

**建议：**

迁移到 `ViewModel + SavedStateHandle`，并设计可版本化的实验会话格式。

### 12. 缺少结构化结果导出和恢复

**建议：**

- 支持导出 CSV/JSON；
- 记录原始输入、相机元数据、SPD 来源、校准质量、饱和率、回归结果和软件版本；
- 支持重新载入会话；
- PDF 可作为后续展示层，不应替代机器可读数据。

### 13. 权限拒绝和不兼容设备的引导不足

**位置：**

- `app/src/main/java/org/fungalsentinel/app/MainActivity.kt:67-96`

**现状：**

相机权限拒绝主要通过短暂 Toast 提示，缺少持久错误页面、重试入口和“前往系统设置”引导。

### 14. 文本资源、本地化和无障碍不足

**位置：**

- `app/src/main/res/values/strings.xml`
- `app/src/main/java/org/fungalsentinel/app/SidebarPanel.kt`
- `app/src/main/java/org/fungalsentinel/app/FssaPanel.kt`

**现状：**

大量界面文本硬编码为英文；窄屏导航使用 `A / 1 / 2 / 3 / 4 / ⚙ / ●` 等符号，首次使用时不够直观。

**建议：**

- 将用户可见文本迁移到字符串资源；
- 至少提供中英文；
- 为图标按钮增加 content description 和清晰选中状态；
- 检查字体缩放、TalkBack 和触控目标尺寸。

### 15. 仪器测试覆盖不足

**位置：**

- `app/src/androidTest/java/org/fungalsentinel/app/ExampleInstrumentedTest.kt`

**现状：**

现有仪器测试主要验证包名和资源，尚未覆盖权限拒绝、旋转、侧边栏、完整四步状态流、拍摄失败和进程恢复。

**建议：**

优先增加不依赖真实 Camera2 的状态/UI 测试，并在 Debug 构建加入固定模拟数据模式；真机 RAW/DNG 测试仍单独执行。

---

## 2026-09-11 P1 实施验证记录

- `./gradlew testDebugUnitTest lintDebug`：成功，39 项 JVM 测试通过，0 失败；
- `./gradlew assembleRelease`：使用本地有效私有签名配置构建成功；
- 临时移除 `keystore.properties` 后，`assembleDebug` 仍成功；`assembleRelease --no-configuration-cache` 按预期失败，错误指向 `MISSING_RELEASE_SIGNING_CONFIG__see_keystore.properties.example`；
- `git diff --check`：通过；
- 新增 `CameraSelectionTest` 和 Bayer 缺失行/真实零值测试；
- 尚待真机验证：拍摄中切后台、连续暂停/恢复、高分辨率 RAW 处理、多后摄选择和 DNG 可读性。

## 推荐实施顺序

1. 波长校准失败拦截；
2. 饱和率和低信号质量控制；
3. 浓度输入及回归质量门槛；
4. 异步状态 revision/输入快照；
5. Camera2 同步异常处理；
6. 将写盘和分析移出相机线程；
7. Release 签名强制检查；
8. SPD 溯源和真机完整四步定量验收；
9. 会话持久化、结果导出和用户体验改进。

## 实施规则

每个问题建议作为独立修改处理：

1. 修改前检查工作区，避免覆盖现有未提交内容；
2. 为缺陷先增加可复现测试；
3. 每次只处理一个问题或一组强相关问题；
4. 执行相关单元测试、Lint 和可行的模拟器/真机验证；
5. 在本文件中更新状态、验证方式和对应提交哈希；
6. 涉及科学阈值时记录阈值来源，不仅凭开发经验决定。
