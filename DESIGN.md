---
version: alpha
name: Mingyuan Mini App
description: Chinese education mini app with compact role dashboards.
colors:
  primary: '#0f58a8'
  text: '#172033'
  muted: '#657184'
  background: '#f4f7fb'
  accent: '#f09a16'
typography:
  sans:
    fontFamily: '-apple-system, BlinkMacSystemFont, Helvetica Neue, Helvetica, Arial, sans-serif'
rounded:
  card: '16rpx'
spacing:
  section: '24rpx'
  teacher-stat-gap: '12rpx'
components:
  statistics-card: {}
---

# Design context

The existing Chinese WeChat education mini app serves parents, teachers and administrators. The runtime owner is WeChatMiniApp/pages/index/index.wxss; this file mirrors existing styles rather than generating tokens.

## Layout

Reuse the homepage statistics card across roles. Four teacher cards occupy one equal-width horizontal row with 12rpx gaps, 10rpx horizontal padding and 20rpx unwrapped labels and action captions. Retain 38rpx figures, card navigation and existing pressed/loading states.

## Visual language

Preserve blue headings and actions, pale backgrounds, white rounded cards, orange role captions, subtle hero shadows and Chinese system font fallbacks. Keep existing wording and interactions when adjusting geometry.