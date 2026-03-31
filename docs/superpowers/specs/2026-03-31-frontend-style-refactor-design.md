# 前端样式整体重构设计

**日期**：2026-03-31
**范围**：Chat 界面 + Admin 后台
**目标**：统一设计语言，建立现代商务型（Linear/Notion 风格）设计系统

---

## 背景与目标

当前前端存在以下问题：
- Chat 侧与 Admin 侧设计语言不统一（蓝黑渐变侧边栏 vs 浅色聊天界面）
- 大量内联魔法值（`text-[14px]`、`#3B82F6`、`bg-[#FAFAFA]`）与 CSS 变量混用
- `globals.css` 中存在两套未对齐的变量系统（旧变量 + "新设计系统"变量）
- 特效过多（渐变光晕卡片、毛玻璃、`tracking-[0.2em]` 等），与专业商务感不符

重构目标：
1. 建立统一的 Design Token 系统（CSS 变量 + Tailwind 扩展）
2. Chat 和 Admin 共享同一套 token，视觉语言一致
3. 所有组件改为 100% 使用 token，消除内联魔法值
4. 风格对齐现代商务型：中性灰主体、Indigo 强调色、克制阴影与圆角

---

## 方案：Design Token 优先

先重建 token 层（`globals.css` + `tailwind.config.cjs`），再系统性更新组件。不大规模重写组件逻辑，只改样式类名与 CSS 变量引用。

---

## Design Token 规范

### 颜色

#### 基础色（中性灰）

```css
/* 背景层级 */
--bg-base:    #FFFFFF;   /* 主内容区 */
--bg-subtle:  #F8F9FA;   /* 次级背景（sidebar、表格头） */
--bg-muted:   #F1F3F5;   /* 悬停、禁用背景 */
--bg-overlay: #E8EAED;   /* 激活态背景 */

/* 文字层级 */
--text-primary:   #111827;  /* 主文字 */
--text-secondary: #4B5563;  /* 次级文字 */
--text-tertiary:  #6B7280;  /* 辅助文字 */
--text-muted:     #9CA3AF;  /* 占位符、弱提示 */
--text-disabled:  #D1D5DB;

/* 边框 */
--border-default: #E5E7EB;
--border-strong:  #D1D5DB;
--border-focus:   #6366F1;
```

#### 强调色（Indigo）

```css
--accent-600:  #4F46E5;  /* 主 CTA、重要操作 */
--accent-500:  #6366F1;  /* hover 态 */
--accent-100:  #EEF2FF;  /* 轻背景（激活 badge、选中项底色） */
--accent-200:  #E0E7FF;  /* 边框强调 */
```

#### Admin 侧边栏（中性深灰）

```css
--sidebar-bg:           #18181B;             /* Zinc-900 */
--sidebar-text:         #A1A1AA;             /* Zinc-400 */
--sidebar-text-active:  #FFFFFF;
--sidebar-item-hover:   rgba(255,255,255,0.06);
--sidebar-item-active:  rgba(255,255,255,0.08);
--sidebar-indicator:    #6366F1;
```

#### 状态色

```css
--color-success: #10B981;
--color-warning: #F59E0B;
--color-error:   #EF4444;
--color-info:    #3B82F6;
```

---

### 圆角

```css
--radius-sm:   6px;    /* 输入框、小 badge */
--radius-md:   8px;    /* 按钮、表格行 */
--radius-lg:   12px;   /* 卡片、模态框 */
--radius-full: 9999px;
```

去掉 `--radius-xl: 20px` 及更大圆角，保持克制。

---

### 阴影

```css
--shadow-xs: 0 1px 2px rgba(0,0,0,0.05);
--shadow-sm: 0 1px 3px rgba(0,0,0,0.08);
--shadow-md: 0 4px 8px rgba(0,0,0,0.06);
--shadow-lg: 0 10px 20px rgba(0,0,0,0.06);
```

---

### 布局尺寸

```css
--sidebar-width:      260px;   /* 从 280px 收窄 */
--content-max-width:  800px;   /* 不变 */
--header-height:      56px;    /* 从 60px 微调 */
```

---

### 字体

保持系统字体栈，不引入额外 web font：
```css
--font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
             "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei",
             "Helvetica Neue", Arial, sans-serif;
--font-mono: "SF Mono", Monaco, "Cascadia Code", "Roboto Mono",
             Consolas, monospace;
```

---

## Chat 界面设计规范

### 侧边栏

- 背景：`--bg-subtle`（#F8F9FA），右侧 `border-default` 分隔线
- 宽度：260px

**品牌区**
去掉蓝色方块图标，改为纯文字：`Ragent` 加粗 + `AI` 用 `--accent-500` 色。右侧放 `+ 新建` 图标按钮（`variant=ghost`）。

**新建对话**
去掉渐变光晕卡片，改为简洁按钮行：
- 高度 36px，`rounded-md`，`variant=outline`
- hover：`bg-muted`

**搜索框**
去掉外层卡片，直接 Input 组件：
- 高度 34px，`rounded-md`，`border-default`，`bg-base`

**会话列表**
- 时间分组标签：11px，字重 500，`text-muted`，`tracking-wide`，全大写
- 会话项：高度 36px，`rounded-md`
  - 默认：`text-secondary`，hover：`bg-muted`
  - 选中：`bg: #EEF2FF`，`text: #4F46E5`
- 去掉所有 `rounded-2xl` 卡片包裹

**底部用户区**
头像 32px 圆形 + 用户名 + MoreHorizontal，hover：`bg-muted`

---

### 消息区

**用户消息气泡**
```
bg: #F1F3F5
radius: 12px 12px 2px 12px
font-size: 14px
color: text-primary
max-width: 80%，右对齐
```

**AI 回复**
无气泡背景，全宽铺开，左对齐，14px，line-height 1.6

---

### 输入框区域

- 去掉毛玻璃效果（`backdrop-blur`）
- 白色背景，顶部 `border-default` 分隔线，`shadow-sm`
- 输入框内部：`rounded-lg`，`border-default`
- 发送按钮：`bg: --accent-600`，`hover: --accent-500`，`radius-md`

---

### 登录页

- 页面背景：`bg-subtle`
- 卡片：白色，`shadow-md`，`radius-lg`，宽度 380px
- 主按钮：Indigo 填充（`--accent-600`）

---

## Admin 后台设计规范

### Admin 侧边栏

- 背景：`#18181B`（Zinc-900）纯色，去掉渐变
- Logo 图标：`#4F46E5` 纯色方块，去掉渐变
- 分组标题：11px，`tracking-wide`，`--sidebar-text`
- 导航项：参见 Design Token 中的 sidebar 变量
- 左侧激活 indicator：`--sidebar-indicator`（#6366F1）
- 头像在线点：`#6366F1`

---

### Admin 顶栏

- 背景：`#FFFFFF`，去掉 `backdrop-blur`
- 底部：`border-default`
- 高度：56px
- 搜索框：`rounded-md`，`border-default`，高度 34px

---

### Admin 内容区

**页面背景**：`#F8F9FA`（`bg-subtle`）

**卡片**
```
border: border-default (#E5E7EB)
shadow: shadow-sm
radius: radius-lg (12px)
```

**表格**
- 表头背景：`bg-subtle`，字号 12px，字重 600，`text-tertiary`
- 行高：44px
- 去掉奇偶行条纹，改为 hover 高亮 `bg-muted`
- 边框颜色：`border-default`

**按钮**
```
primary:  bg: #4F46E5，hover: #4338CA
outline:  border: border-default，text: text-secondary，hover: bg-muted
ghost:    text: text-tertiary，hover: bg-muted
```

**Badge**
```
default:     bg: #EEF2FF，text: #4F46E5，border: #E0E7FF
secondary:   bg: #F1F3F5，text: text-secondary
destructive: bg: #FEF2F2，text: #EF4444
```

---

### Trace 监控页

`--trace-*` 变量名保留（不改组件），只对齐变量值到新 token：

```css
--trace-border      → #E5E7EB  (border-default)
--trace-bg-surface  → #FFFFFF  (bg-base)
--trace-bg-muted    → #F8F9FA  (bg-subtle)
--trace-bg-subtle   → #F1F3F5  (bg-muted)
--trace-text-primary   → #111827
--trace-text-secondary → #4B5563
--trace-text-weak      → #6B7280
```

---

## 实施原则

1. **Token 优先**：组件中不得出现内联颜色值（如 `bg-[#3B82F6]`）、内联字号（如 `text-[14px]`）。统一使用 CSS 变量或 Tailwind token。
2. **只改样式**：不改组件逻辑、状态管理、API 调用。
3. **保持组件兼容**：`--trace-*` 等旧变量名保留，只改值；shadcn/ui 组件通过 `.admin-layout` 作用域覆盖。
4. **不引入新依赖**：不新增字体库、图标库或动画库。

---

## 文件变更范围

| 文件 | 变更类型 |
|------|----------|
| `frontend/src/styles/globals.css` | 重建 token 变量，更新 admin/chat 组件样式 |
| `frontend/tailwind.config.cjs` | 扩展 colors/shadow/radius 映射到新 token |
| `frontend/src/components/layout/Sidebar.tsx` | 样式类名更新 |
| `frontend/src/components/layout/MainLayout.tsx` | 样式类名更新 |
| `frontend/src/components/layout/Header.tsx` | 样式类名更新 |
| `frontend/src/components/chat/ChatInput.tsx` | 去掉毛玻璃，更新输入框样式 |
| `frontend/src/components/chat/MessageItem.tsx` | 用户气泡样式更新 |
| `frontend/src/components/chat/WelcomeScreen.tsx` | 样式更新 |
| `frontend/src/pages/LoginPage.tsx` | 页面背景 + 卡片样式更新 |
| `frontend/src/pages/admin/AdminLayout.tsx` | 侧边栏 + 顶栏样式更新 |
| `frontend/src/pages/admin/**/**.tsx` | 按需更新内联颜色为 token |
