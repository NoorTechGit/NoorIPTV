---
name: Model usage preference
description: User wants Sonnet by default, Opus only when truly necessary (complex architecture decisions, hard bugs)
type: feedback
---

Use Sonnet (default) for most tasks. Only use Opus when it's truly necessary (complex architecture, hard debugging).

**Why:** User wants faster responses and lower cost for routine work.
**How to apply:** Default all Agent calls to model="sonnet" unless the task requires deep architectural reasoning.
