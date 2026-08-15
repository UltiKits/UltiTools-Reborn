# Security Policy

[English](#reporting-a-vulnerability) · [中文](#安全问题报告)

## Supported Versions

Only the **latest released version** receives security fixes — at the time of
writing, **6.2.4**. If that number has aged, the
[Releases page](https://github.com/UltiKits/UltiTools-Reborn/releases/latest)
is authoritative; whatever sits there is the supported version, and everything
before it is not.

There are no maintained release branches, so a fix is delivered by upgrading
rather than by backporting. If you are running an older 6.x — or any 5.x —
please upgrade before reporting.

## Reporting a Vulnerability

**Please do not open a public issue for a security problem.** A public issue is
visible to everyone the moment you press submit, including the operators of
servers that have not been patched yet.

Use GitHub's private reporting instead:

> **Security** tab → **Report a vulnerability**
> <https://github.com/UltiKits/UltiTools-Reborn/security/advisories/new>

That channel is enabled on this repository, and only the maintainers can see
what you write there.

Include whatever you have: the affected version, what an attacker can actually
do, and the smallest set of steps that demonstrates it. A partial report is
still worth sending — please don't sit on something because it feels
incomplete.

### What to expect

- **A first response within 14 days.** That means an acknowledgement and an
  initial assessment, not necessarily a fix.
- **No promised fix deadline.** This project is maintained in spare time, and a
  date that gets missed is worse than no date at all. Once the report has been
  assessed you'll be told what the plan is.
- **Disclosure through a GitHub Security Advisory** once a fix has shipped.
- **Credit is yours to choose.** You can be named in the advisory or stay
  anonymous — say which you prefer when you report.

### Out of scope

These are real answers rather than brush-offs; reports in these categories will
be closed with a pointer back here.

- **Anything that requires server-operator privileges to trigger.** An operator
  can already run arbitrary commands, so a framework feature reachable only by
  an operator is not a privilege boundary.
- **Known advisories in upstream dependencies.** Those are tracked by
  Dependabot on this repository and handled there.
- **The UltiPanel service.** The panel backend is a separate system and is not
  part of this repository.

---

## 安全问题报告

### 支持的版本

只有**最新的正式发布版本**会收到安全修复 —— 写这份文档时是 **6.2.4**。如果这个数字
已经旧了，以[发布页](https://github.com/UltiKits/UltiTools-Reborn/releases/latest)为准：
挂在那里的就是受支持的版本，在它之前的都不是。

本项目没有维护中的发布分支，所以修复是通过**升级**交付的，不做向后移植。如果你在跑
更老的 6.x 或者任何 5.x，请先升级再报告。

### 怎么报告

**请不要为安全问题开公开 issue。** 公开 issue 在你按下提交的那一刻就是所有人可见的，
包括那些还没打补丁的服务器的管理员。

请走 GitHub 的私密报告通道：

> 仓库 **Security** 标签页 → **Report a vulnerability**
> <https://github.com/UltiKits/UltiTools-Reborn/security/advisories/new>

这个通道在本仓库已经启用，你在那里写的内容只有维护者能看到。

有什么就写什么：受影响的版本、攻击者实际能做到什么、以及能复现的最少步骤。信息不全
也值得发出来 —— 不要因为觉得「还没查清楚」就压着不报。

#### 你会得到什么

- **14 天内首次回应。** 是确认收到加初步判断，不一定是修复。
- **不承诺修复期限。** 这个项目是业余时间维护的，一个到期没做到的日期比不给日期更糟。
  评估完之后会告诉你接下来的打算。
- **修复发布后通过 GitHub Security Advisory 公开。**
- **署名与否由你决定。** 可以在 advisory 里具名致谢，也可以匿名 —— 报告时说一声即可。

#### 不在范围内

下面几类是真实答复，不是敷衍；属于这些情况的报告会被关闭并指回本文。

- **任何需要服务器管理员权限才能触发的行为。** 管理员本来就能执行任意指令，只有管理员
  才够得到的框架功能不构成一条权限边界。
- **上游依赖的已知告警。** 那些由本仓库的 Dependabot 跟踪并在那边处理。
- **UltiPanel 面板服务端。** 面板后端是独立系统，不属于本仓库。
