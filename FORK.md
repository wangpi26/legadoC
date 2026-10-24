# Reader Next fork 说明

- 上游: https://github.com/CCSSNE/legadoC (branch `own`)
- 本 fork: https://github.com/wangpi26/legadoC (branch `feature/readernext`)
- 基线提交: `4301f639` (2026-10-25 拉取)
- 许可证: 仓库根 `LICENSE` (GPL-PLUS, 要求基于本项目代码的衍生作品**完全开源**)。本 fork 为公开仓库, 修改与发布必须继续公开源码并保留上游署名。

## 分支约定

- `own`: 跟踪上游发布分支, 只做同步, 不直接提交产品改动。
- `feature/readernext`: 我们的产品化分支; 所有自研功能提交到这里, 定期 rebase/merge `own`。

## 构建

- 入口: 根目录 Gradle Wrapper; 产品变体为 `appC` (见 `app/build.gradle` 与上游 `AGENTS.md`)。
- 本机 Android 环境见 Harness 根目录 `env.sh`; 具体命令后续补入 Harness `docs/operations/`。

## 我们的功能方向

- 阅读体验保持上游基线, 只做增量改造, 不再自研阅读内核。
- 上游 AI 已覆盖章节净化、多角色朗读、分镜批量分析与自动选音; 不重复造这些链路。
- 当前增量: 跨书角色库 (跨书查看/搜索/编辑/合并/删除角色, 入口在阅读页 More 与 AI 多角色弹窗); 角色归档导出/导入 (JSON, 重建 id, 同名跳过, SAF); 跨书同名正式角色音色同步 (默认跳过已有绑定, 可覆盖, manual)。
- 后续候选: 角色导入/导出、角色音色批量处理、模型按需下载 (尚未开始)。
