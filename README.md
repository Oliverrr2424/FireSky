# FireSky

火烧云 / 朝霞晚霞预测前端，部署在 Cloudflare Pages（含 Pages Functions）。

- 生产站点: [https://fireskychase.pages.dev](https://fireskychase.pages.dev)
- 仓库: [https://github.com/Oliverrr2424/FireSky](https://github.com/Oliverrr2424/FireSky)

## 技术栈

- Vite + React 前端 (`src/`)
- Cloudflare Pages Functions (`functions/api/*`)
- Cloudflare KV 缓存 (binding `FIRESKY_CACHE`)
- Open-Meteo / 自建数据源
- 训练与建模脚本 (`scripts/`, `data_sources/`, `docs/ml-training-dataset.md`)

## 环境要求

- Node.js 20+（建议 22，对齐 Cloudflare 构建环境）
- npm 10+
- 可选: Python 3.10+（仅用于 `scripts/` 下的数据/训练脚本）

## 本地开发

```bash
npm install

# 仅启前端（最快，能调 UI/样式/前端逻辑）
npm run dev

# 跑完整 Pages（含 functions/api/* 等 Worker）
npm run build
npx wrangler pages dev dist
```

`npm run dev` 默认监听 `http://127.0.0.1:5173`。  
`wrangler pages dev` 默认监听 `http://127.0.0.1:8788`，会自动加载本地 KV 模拟、`.dev.vars` 环境变量。

### 开发模式怎么选

- 只改前端 UI / 交互 / 样式：用 `npm run dev`（启动快，热更新快）
- 要联调 `functions/api/*`、KV、Cloudflare 运行时：用 `npm run build && npx wrangler pages dev dist`
- 看到页面提示 `Weather data is temporarily unavailable`：通常是你只开了 Vite 前端，没有启动 Pages Functions；切到 `wrangler pages dev` 即可

### 常用命令速查

```bash
# 前端快速开发（不含 functions）
npm run dev

# 完整本地联调（含 functions + KV 模拟）
npm run build
npx wrangler pages dev dist --ip 127.0.0.1 --port 8788 --kv FIRESKY_CACHE

# 本地静态预览（不含 functions）
npm run build
npm run preview
```

> 端口占用提示：如果 `5173` 被占用，Vite 会自动切到 `5174`/`5175` 等端口；以终端输出的 `Local:` 地址为准。

### 本地环境变量

在仓库根目录建 `.dev.vars`（已被 `.gitignore` 忽略），写入开发用变量，例如：

```
OPEN_METEO_KEY=local-debug
API_BASE_URL=http://127.0.0.1:8788
```

线上变量在 Cloudflare Pages 项目的 Settings → Variables and Secrets 配置，Production / Preview 分别维护。

## 构建产物

```bash
npm run build      # 输出到 dist/
npm run preview    # 用 vite preview 启静态文件预览
```

## 部署

部署目标: Cloudflare Pages 项目 `firesky`（生产域名 `fireskychase.pages.dev`）。

### 自动部署（推荐）

仓库 GitHub 已经接到 Cloudflare Pages：

- `push` 到 `master` → 自动构建并发布到生产环境
- `push` 到其它分支 / PR → 自动构建并产出 Preview 部署（独立 URL）

构建配置：

- Build command: `npm run build`
- Build output directory: `dist`
- Production branch: `master`

> 注意: 仓库未提交 `package-lock.json`（Windows 上生成的 lockfile 在 Linux CI 上会因为缺少 `@emnapi/*` 等平台可选依赖而 `npm ci` 失败）。CI 因此走 `npm install`，本地 lockfile 仍会按需在 `node_modules/` 中生成。

### 手动部署（备用）

```bash
npm run build
npx wrangler pages deploy dist --project-name firesky --branch master
```

> 旧项目 `fire-sky-now`（`fire-sky-now.pages.dev`）是早期通过 Direct Upload 创建的，已被 `firesky` 取代。

## 移动端（Capacitor）

已支持使用 Capacitor 打包到 Android / iOS。

```bash
# 首次安装依赖
npm install

# Web 构建 + Capacitor 同步
npm run mobile:build

# 体检（会在 Windows 上提示缺少 Xcode，属正常）
npm run mobile:doctor

# 打开原生工程
npm run mobile:open:android
npm run mobile:open:ios
```

发布与合规文档：

- `docs/mobile/release-runbook.md`
- `docs/mobile/privacy-policy-template.md`
- `docs/mobile/app-store-submission.md`
- `docs/mobile/google-play-submission.md`

## Cloudflare 资源

- Pages 项目: `firesky`
- KV namespace: `FIRESKY_CACHE`，id `dc605f9c3d0b4e429709b17dd91e802a`
- 见 `wrangler.toml`

如果新增 Preview 专用 KV，可在 `wrangler.toml` 的 `[[kv_namespaces]]` 中追加 `preview_id`。

## 目录结构（节选）

```
src/                 前端源码 (React + Vite)
functions/api/       Pages Functions (Cloudflare Workers 路由)
public/              静态资源 (favicon, manifest, icon)
scripts/             数据抓取与模型训练 Python 脚本
data_sources/        数据集（部分大数据集已 .gitignore，未入库）
docs/                算法与训练说明
wrangler.toml        Cloudflare 配置（Pages + KV binding）
```

## 已忽略目录

为避免上传大文件 / 临时产物，以下被 `.gitignore`：

- `node_modules/`, `dist/`, `.vite/`
- `.wrangler/`（本地 KV / 中间产物）
- `*.log`
- `__pycache__/`, `*.pyc`
- `data_sources/modeling/`, `data_sources/modeling_v2/`, `data_sources/raw/`
- `package-lock.json`（见上文）

## 开发工作流建议

1. 在 `dev` 等非 `master` 分支上开发，push 后用预览部署地址联调。
2. 验证 OK 后合并到 `master`，自动发布生产。
3. 修改 Functions 时建议用 `wrangler pages dev dist` 在本地预跑，避免直接打到生产。

