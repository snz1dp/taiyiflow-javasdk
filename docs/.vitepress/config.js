import { defineConfig } from 'vitepress'
import { existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { withMermaid } from 'vitepress-plugin-mermaid'

// 支持双渠道部署：
// - Docker 镜像：base = '/taiyi/javasdk/docs/'（默认，与 docs/BUILD.yaml 的 WEB_CONTEXT_PATH 一致，
//   运行时可通过 WEB_CONTEXT_PATH 环境变量重定位）
// - GitHub Pages：base = '/taiyiflow-javasdk/'（通过 VITEPRESS_BASE_PATH 环境变量）
const basePath = process.env.VITEPRESS_BASE_PATH || '/taiyi/javasdk/docs/'

// Docker 构建时 .git 被 .dockerignore 排除，lastUpdated 依赖 git log，需按环境禁用
const __dirname = dirname(fileURLToPath(import.meta.url))
const hasGit = existsSync(resolve(__dirname, '../../.git'))

const config = defineConfig({
  title: '太乙智启 Java 开发手册',
  description: '太乙智启 Java Agent SDK 使用手册：TaiyiAgent 智能体调用、DigitalWorker 数字人运行时、Hook 扩展与容器部署',
  lang: 'zh-CN',
  base: basePath,

  // 构建输出目录
  outDir: '.vitepress/dist',

  // 顶层开关：无 .git 时禁用（避免 Docker 构建时 spawn git ENOENT）
  lastUpdated: hasGit,

  // 忽略 localhost 等开发环境链接的死链检查
  ignoreDeadLinks: [
    /^https?:\/\/localhost/,
    /^https?:\/\/127\.0\.0\.1/
  ],

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: `${basePath}favicon.png` }]
  ],

  themeConfig: {
    logo: undefined,
    siteTitle: '太乙智启 Java SDK',

    nav: [
      { text: '首页', link: '/' },
      { text: '快速开始', link: '/getting-started' },
      { text: '核心能力', link: '/agent-and-session' },
      { text: '数字人', link: '/digital-worker' },
      { text: 'Spring Boot', link: '/spring-boot' },
      { text: '部署', link: '/deployment' },
      { text: '源码仓库', link: 'https://gitlab.snz1.cn/dp/ai/taiyi/taiyiflow-javasdk', target: '_blank' }
    ],

    sidebar: [
      {
        text: '入门',
        items: [
          { text: '快速开始', link: '/getting-started' },
          { text: 'TaiyiAgent 与 AgentSession', link: '/agent-and-session' },
          { text: '示例程序', link: '/examples' }
        ]
      },
      {
        text: '核心能力',
        items: [
          { text: 'Hook 体系与权限审批', link: '/hooks' },
          { text: '内置工具插件', link: '/plugins' },
          { text: 'MCP 接入', link: '/mcp' },
          { text: '执行控制与恢复', link: '/execution-control' }
        ]
      },
      {
        text: '数字人与协同',
        items: [
          { text: 'DigitalWorker 数字人运行时', link: '/digital-worker' },
          { text: '协同插件', link: '/collaboration' }
        ]
      },
      {
        text: '集成与部署',
        items: [
          { text: 'Spring Boot 集成', link: '/spring-boot' },
          { text: '构建与容器部署', link: '/deployment' },
          { text: '单实例容器运维手册', link: '/single-instance-container' }
        ]
      },
      {
        text: '参考',
        items: [
          { text: '传输协议参考', link: '/protocol' }
        ]
      }
    ],

    search: {
      provider: 'local'
    },

    outline: {
      level: [2, 3],
      label: '页面导航'
    },

    docFooter: {
      prev: '上一篇',
      next: '下一篇'
    },

    lastUpdated: hasGit ? { text: '最后更新于' } : false,

    footer: {
      message: '太乙智启 Java SDK',
      copyright: '© 2026 长沙慧码至一信息科技有限公司'
    }
  },

  mermaid: {
    // mermaid 初始化配置
    theme: 'default'
  },

  vite: {
    optimizeDeps: {
      // mermaid 的 ESM 入口以 default import 引入 fastdom（UMD/CJS），
      // dev 模式下必须显式预构建做 CJS→ESM 互操作，否则浏览器报
      // "does not provide an export named 'default'" 导致整站白屏。
      // 注意：这里必须整体覆盖 withMermaid 注入的默认 include 列表——
      // 它包含 mermaid@11.17 已不再依赖的 debug，会导致预构建失败。
      include: [
        'fastdom',
        'fastdom/extensions/fastdom-promised.js',
        '@braintree/sanitize-url',
        'dayjs',
        'cytoscape',
        'cytoscape-cose-bilkent'
      ]
    }
  }
})

export default withMermaid(config)