---
name: vibetype-capsules
description: Read, digest and act on VibeType thought capsules. Use when the user mentions a capsule, thought, idea, "I just thought of", "note this" — or 胶囊 / 闪念 / 灵感 / 我刚想到的 / 记一下.
---

# VibeType capsules

## What this is

VibeType is a voice-first thought tool on the phone. The user holds the capsule key, says one
thing, and the phone transcribes it locally into a capsule. Capsules sync to the desktop when
connected. A capsule is an **inbox of raw thoughts**: it has no category, no due date, and it
does not mean the user committed to doing it.

## Why it works this way

The starting point is Smartisan's idea pills: thoughts arrive fast and leave fast. Unlocking the
phone, finding an app, creating a note and typing are already enough steps for someone to give
up. So there is one key: press it, speak, and sort it out later.

That leads to three rules:

- A capsule is **raw material**, not a todo and not a finished note. Don't turn every one into a task.
- The user may speak half a sentence with no context. Reconstruct what they were thinking; don't read it literally.
- The value is in the **next step**, not in filing. Sorting without an action is not sorting.

## Standard flow

1. `capsules_list` with `status: "new"`. Don't pull everything at once.
2. Use `capsules_get` when you need the full text; list entries may be truncated.
3. Synthesize: group by theme, point out repetition, contradictions and things that can be done
   immediately. If information is missing, ask one question — don't invent a plan.
4. After the user confirms, mark those ids `used` with `capsules_mark`. **Never mark on their behalf.**
5. When the result belongs back in what they are writing, use `text_type` (types at the cursor).

## When to create

Only when the user says "note this", "don't forget", "put it in capsules": `capsules_create`.
Don't stockpile capsules from the conversation on your own — the inbox belongs to the user.

## Tone

Short and concrete, like a reminder from a colleague. No "Here is a summary:" boilerplate.
At most three next steps, and say which one is worth doing today.

## Boundaries

- Don't read transcript history: VibeType does not expose it over MCP.
- Don't delete capsules without explicit consent.
- `keys_press` is off by default; don't assume it is available.

---

# 闪念胶囊

## 这是什么

VibeType 是手机上的语音闪念工具。用户按下胶囊键说一句话，声音在手机本地转成文字，
存成一条「胶囊」，连上电脑时同步到桌面。胶囊是**灵感收件箱**：记下来的时候不分类、
不加截止日期，也不代表用户承诺要做。

## 为什么这样设计

参考锤子手机闪念胶囊的出发点：灵感来得快、去得也快。解锁、找 App、新建、打字，
这几步就足够让人放弃记录。所以入口只有一个按键，按一下说一句，剩下的以后再说。

由此得到三条判断：

- 胶囊是**原材料**，不是待办、也不是笔记终稿。不要把每条都变成任务。
- 用户可能只说半句话，缺上下文。理解时先还原「他当时在想什么」，不要抠字面。
- 整理的价值在**下一步**，不在归档。归完类没有动作，等于没整理。

## 标准流程

1. `capsules_list` 取胶囊，优先 `status: "new"`；不要一上来就拉全部。
2. 需要全文时用 `capsules_get`，列表里的正文可能被截断。
3. 归纳：按主题聚类，指出重复、矛盾、可以立刻做的点。信息不够就直接问一个问题，
   不要编一个计划。
4. 用户确认后，用 `capsules_mark` 把这些 id 标成 `used`。**不要替用户默认标记。**
5. 结果需要放回用户正在写的地方时，用 `text_type`（打进当前光标）。

## 什么时候新建

用户说「记一下」「别忘了」「存进胶囊」时，用 `capsules_create`。
不要凭对话内容自动囤胶囊——收件箱是用户的，不是你的。

## 语气

短、具体，像同事之间的提醒。不用「您」，不写「以下是整理结果：」这类套话。
一次最多给三个下一步，并指出哪一件最值得今天做。

## 边界

- 不读转写历史：VibeType 不通过 MCP 暴露它。
- 不主动删除胶囊；删除需要用户明确同意。
- `keys_press` 默认关闭，不要假设它可用。
