---
layout: home

hero:
  name: "太乙智启 Java SDK"
  text: "让 Java 后台拥有智能体能力"
  tagline: 几行代码驱动平台智能体多会话并行执行，或让 Java 进程成为常驻接活、请示、回报的数字员工
  actions:
    - theme: brand
      text: 快速开始
      link: /getting-started
    - theme: alt
      text: 智能体与会话
      link: /agent-and-session
    - theme: alt
      text: 智能体数字人
      link: /digital-worker

features:
  - icon: 🤖
    title: 双形态运行时
    details: TaiyiAgent 作为调用者主动驱动平台智能体；DigitalWorker 常驻协同连接接收平台下发任务，一个 SDK 覆盖两种接入形态。
  - icon: ⚡
    title: 多会话并行
    details: 基于 Java 21 虚拟线程，一个 TaiyiAgent 实例管理多个并发 AgentSession，会话间隔离、可各自暂停、恢复与中断。
  - icon: 🪝
    title: 钩子（Hook）扩展体系
    details: 流式输出、工具接管、权限审批、会话生命周期四类钩子（Hook），业务定制不侵入 SDK；不挂钩子即走内置默认行为。
  - icon: 🔧
    title: 内置工具与 MCP
    details: Shell、文件、搜索等 13 个内置工具开箱即用，支持 Streamable HTTP 与 stdio 两种 MCP 形态，统一经过权限与审批链。
  - icon: 🛡️
    title: 安全可控
    details: READONLY / CONFIRM / AUTO 三级权限策略，工具沙盒与命令白名单，ASK 审批闭环，敏感操作尽在掌控。
  - icon: 🚀
    title: 生产级可靠性
    details: 断线自动重接（attach）/ 检查点（checkpoint）恢复，结果补传发件箱（Outbox）只补传不重跑，单实例容器部署，Spring Boot 启动器（Starter）自动装配。
---