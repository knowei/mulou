# App Brand & Icon Design: 木漏 (Komo)

本文档归档了专为本动漫客户端设计的品牌名称、视觉理念与图标资源，便于后续 Agent 或开发者将设计直接接入 Android 客户端工程。

---

## 1. 品牌与名称设定 (Brand & Naming)

- **中文名称**：木漏（或「木漏日」）
- **英文名称**：**Komo**（全称：*Komorebi*）
- **语源出处**：日文「木漏れ日（こもれび）」，意为阳光穿透树叶缝隙洒落下的斑驳光影。
- **设计心智定位**：
  - **纯净与无干扰**：作为一款纯粹的聚合嗅探与播放器工具，追求吉卜力动画般的宁静、温暖与治愈感。
  - **去工具化、强调艺术感**：去除了生硬陈旧的播放三角键（▶），将追番体验沉淀为闲暇生活里一段私密惬意的晨光时光。

---

## 2. 视觉资源清单 (Design Assets)

所有设计原图均已保存在当前 `design/` 目录下：

| 文件名 | 风格分类 | 状态说明 |
| :--- | :--- | :--- |
| [`icon_komo_v2_featured.jpg`](file:///d:/pro/work/proj/DSH/dm/design/icon_komo_v2_featured.jpg) | **琉光微境 · 磨砂质感 (Featured)** | ★ **主推推荐**。中心为温润金阳微芒，流线圆环与立体雕塑绿叶环抱，质感高级耐看。 |
| [`icon_komo_v1_natural.jpg`](file:///d:/pro/work/proj/DSH/dm/design/icon_komo_v1_natural.jpg) | 晨曦穿林 · 自然清爽 | 备选方案。光影穿透感更强烈，枝叶形态舒展。 |
| [`icon_komo_v3_minimal.jpg`](file:///d:/pro/work/proj/DSH/dm/design/icon_komo_v3_minimal.jpg) | 纯净空灵 · 极简圆弧 | 备选方案。几何线条感更强，适合极简派。 |
| [`icon_komo_with_play_triangle_legacy.jpg`](file:///d:/pro/work/proj/DSH/dm/design/icon_komo_with_play_triangle_legacy.jpg) | 早期带播放键版本 | 归档保留供历史对比。 |

---

## 3. 调色板与主题色 (Color Palette)

供 Android Compose Material 3 主题与图标配置参考：

| 角色 | 颜色值 (Hex) | 用途 |
| :--- | :--- | :--- |
| **Sage Primary (莫兰迪青绿)** | `#7F998A` / `#6E8B7A` | 主题主色、图标背景基底、关键交互高亮 |
| **Sage Container (浅青灰)** | `#E0EAE2` | 浅色模式卡片底色、柔和衬底 |
| **Sun Amber (暖金晨曦)** | `#FCD074` / `#F8BE50` | 强调色、发光粒子、进度条与微光核心 |
| **Soft Cream (晨光米白)** | `#FFFDF9` | 浅色模式背景、文字高亮 |
| **Deep Forest (深邃黛绿)** | `#151E1A` / `#0F1613` | 深色模式 (Dark Theme) 背景底色 |

---

## 4. 后续接入指南 (Implementation Guide for Next Agent)

若需将此设计正式落地到 Android 工程，后续 Agent 可执行以下工作：

### 4.1 应用名称修改
在 [strings.xml](file:///d:/pro/work/proj/DSH/dm/app/src/main/res/values/strings.xml) 中更新应用名：
```xml
<resources>
    <string name="app_name">木漏</string>
    <!-- 或保留英文名 -->
    <!-- <string name="app_name">Komo</string> -->
</resources>
```

### 4.2 图标切图与自适应图标配置 (Adaptive Icons)
- **推荐主图**：使用 `icon_komo_v2_featured.jpg` 作为切图源。
- **Android Adaptive Icon (API 26+)**：
  - 前景层 (`res/drawable/ic_launcher_foreground.xml` 或 `.png`)：中心金光暖阳 + 环抱式叶片几何。
  - 背景层 (`res/drawable/ic_launcher_background.xml`)：柔和莫兰迪青绿渐变或纯色 `#6E8B7A`。
- **各分辨率位图切图输出路径**（如需生成普通 mipmap PNG）：
  - `app/src/main/res/mipmap-mdpi/ic_launcher.png` (48x48)
  - `app/src/main/res/mipmap-hdpi/ic_launcher.png` (72x72)
  - `app/src/main/res/mipmap-xhdpi/ic_launcher.png` (96x96)
  - `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (144x144)
  - `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (192x192)
  - Google Play / 分发商店高分辨率图标 (512x512)
