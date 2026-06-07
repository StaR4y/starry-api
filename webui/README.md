# Starry API WebUI

Starry API 的 Web 控制台。提供管理员登录、上游账号池维护、API Key 下发、模型映射、平台模型目录和请求日志查看等能力。

## 技术栈

| 维度 | 选型 | 说明 |
| --- | --- | --- |
| 框架 | **Vue 3** + `<script setup>` + TypeScript | 组合式 API，类型安全 |
| 构建 | **Vite 5** | 极快冷启动 / HMR |
| 组件库 | **Element Plus** | 中文后台首选，按需自动引入 |
| 状态 | **Pinia** | 轻量、模块化 |
| 路由 | **Vue Router 4**（hash 模式） | 与后端无路径耦合，方便嵌入 `/admin` 子路径部署 |
| HTTP | **Axios** | 拦截器统一处理 token、错误、`ApiResponse` 解包 |
| 样式 | **SCSS** + 磨砂玻璃（Glassmorphism） | 通过 CSS 变量集中维护主题 |

## 目录结构

```
webui/
├── public/                # 静态资源（favicon 等）
├── index.html             # Vite 入口模板
├── vite.config.ts         # Vite & 自动按需引入配置
├── tsconfig.json          # TS 配置（含路径别名 @/*）
├── src/
│   ├── main.ts            # 应用入口：挂载 Pinia / Router / 全局样式
│   ├── App.vue            # 根组件
│   ├── api/               # 业务 API 层（按模块拆分）
│   │   ├── auth.ts
│   │   ├── users.ts
│   │   ├── accounts.ts
│   │   ├── apiKeys.ts
│   │   ├── modelMappings.ts
│   │   ├── logs.ts
│   │   ├── system.ts      # 健康检查 / 模型清单
│   │   └── index.ts       # 统一出口
│   ├── assets/styles/     # 全局样式（含玻璃主题）
│   ├── components/        # 可复用 UI 组件
│   ├── composables/       # 组合式逻辑（按需添加）
│   ├── layouts/
│   │   └── DefaultLayout.vue
│   ├── platforms/         # 前端平台注册、模型推断、客户端脚本
│   │   ├── openai/        # OpenAI 展示元数据与客户端环境变量片段
│   │   ├── anthropic/     # Anthropic / Claude Code 展示元数据与脚本片段
│   │   ├── gemini/        # Gemini 展示元数据与脚本片段
│   │   ├── opencode/      # OpenCode 项目配置与脚本片段
│   │   ├── registry.ts    # 统一 provider / family 顺序与模型推断
│   │   ├── clientSetup.ts # 聚合各平台片段，生成 macOS/Linux/Windows 脚本
│   │   ├── setupShared.ts # 脚本生成公共工具
│   │   └── types.ts       # 平台与脚本类型
│   ├── router/index.ts    # 路由 + 全局守卫
│   ├── stores/auth.ts     # 鉴权状态
│   ├── types/api.ts       # 与后端 DTO 对齐的类型
│   ├── utils/
│   │   ├── http.ts        # Axios 实例 + 拦截器
│   │   └── format.ts      # 时间 / 数字格式化
│   └── views/             # 业务页面（按模块分目录）
│       ├── auth/          # 登录、改密
│       ├── dashboard/
│       ├── accounts/
│       ├── api-keys/
│       ├── model-mappings/
│       ├── logs/
│       └── users/
└── README.md
```

### 模块化约定

- **每个业务模块** 在 `src/api/` 下有一个文件，导出一个 `xxxApi` 对象，所有方法返回已解包的业务数据。
- **类型** 集中在 `src/types/api.ts`，命名与后端 Java DTO 一一对应；后续若引入 OpenAPI 自动生成，可整体替换该文件。
- **视图** 按业务模块在 `src/views/<module>/` 下组织，文件命名遵循 `XxxView.vue`，便于路由 lazy import。
- **平台注册** 集中在 `src/platforms/`；每个平台的展示元数据放在 `src/platforms/<provider>/index.ts`，客户端配置片段放在 `src/platforms/<provider>/setup.ts`。
- **平台聚合** 只在 `src/platforms/registry.ts` 和 `src/platforms/clientSetup.ts` 完成；页面组件不要写 provider/family 推断或环境变量块。
- **全局基础组件**（`PageSection.vue` / `StatusTag.vue`）放在 `src/components/`；只属于单个视图的组件放在该视图目录下。
- **路由元信息** 通过 `meta.title / meta.requiresAuth / meta.allowFirstLogin` 描述行为，由全局守卫统一处理。
- **样式** 优先使用 `:root` 中定义的 CSS 变量；新增主题色或圆角请扩展该变量集，而不是写死值。

## 开发

```bash
cd webui
npm install
cp .env.example .env        # 修改 VITE_API_BASE 为你的后端地址
npm run dev
```

默认在 `http://127.0.0.1:5173`，开发服务器会把 `/auth /admin /v1 /health /models /chat /responses /actuator` 这些后端路径代理到 `VITE_API_BASE`。

> 第一次部署 Spring Boot 后，启动日志中会打印一次性的 admin 随机密码，使用 `admin` + 该密码登录。

## 构建与部署

```bash
npm run build
```

作为 Gradle 模块时，也可以在仓库根目录执行：

```bash
./gradlew :webui:npmBuild
./gradlew :webui:npmLint
./gradlew :webui:npmDev
```

产物位于 `webui/dist/`，所有路径使用相对引用（`base: './'`），可以直接：

1. **同源部署**：把 `dist/` 拷贝到任何静态服务器（Nginx / OSS / CDN），后端反向代理或同源均可。
2. **嵌入后端**：执行 `./gradlew :service:bootJar`，构建会把 `dist/` 打进 Spring Boot jar，访问 `http://<host>/#/login` 即可。

由于使用 hash 路由（`/#/login`），不需要单独配置 SPA fallback。

## 代码规范

- `npm run lint`：ESLint（vue/vue3-recommended）
- `npm run format`：Prettier
- 所有公共函数 / 组件 / API 方法都附带顶部 JSDoc/块注释，描述用途、副作用与边界条件。
- 业务页面使用 `<script setup lang="ts">`，避免 Options API 与 setup 混用。

## 二次开发指引

新增一个业务模块的最少改动：

1. 在 `src/types/api.ts` 增加对应 DTO。
2. 在 `src/api/<module>.ts` 增加调用层，并在 `src/api/index.ts` 出口处注册。
3. 在 `src/views/<module>/` 增加 `XxxView.vue`。
4. 在 `src/router/index.ts` 注册路由 + `meta.title`。
5. 在 `src/layouts/DefaultLayout.vue` 的 `menus` 数组增加菜单项。

每一步都是局部修改，模块之间无隐式耦合。
