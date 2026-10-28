const COLORS = ['#e74c3c', '#1768c2', '#1b9a68', '#172033']
Page({
  data: { ready: false, tool: 'pen', color: '#e74c3c', colors: COLORS, canvasW: 300, canvasH: 400, textHint: '' },
  strokes: [], cur: null, texts: [], placingText: '', img: null, ctx: null, dpr: 1, drag: null,
  onLoad() {
    const ch = this.getOpenerEventChannel()
    ch.on('init', ({ src }) => this.init(src))
  },
  init(src) {
    const q = wx.createSelectorQuery()
    q.select('#cv').fields({ node: true, size: true }).exec(res => {
      const node = res[0].node
      const sys = wx.getSystemInfoSync()
      const maxW = sys.windowWidth - 32
      this.dpr = sys.pixelRatio || 2
      wx.getImageInfo({ src, success: info => {
        const scale = Math.min(maxW / info.width, 520 / info.height)
        const w = Math.round(info.width * scale), h = Math.round(info.height * scale)
        node.width = w * this.dpr; node.height = h * this.dpr
        this.setData({ canvasW: w, canvasH: h, ready: true })
        const ctx = node.getContext('2d')
        ctx.scale(this.dpr, this.dpr)
        this.node = node; this.ctx = ctx
        this.img = node.createImage()
        this.img.onload = () => this.redraw()
        this.img.src = src
      }, fail: () => wx.showToast({ title: '图片加载失败', icon: 'none' }) })
    })
  },
  redraw() {
    const ctx = this.ctx; if (!ctx) return
    ctx.clearRect(0, 0, this.data.canvasW, this.data.canvasH)
    if (this.img) ctx.drawImage(this.img, 0, 0, this.data.canvasW, this.data.canvasH)
    ctx.lineCap = 'round'; ctx.lineJoin = 'round'
    this.strokes.forEach(s => { ctx.strokeStyle = s.color; ctx.lineWidth = s.size; ctx.beginPath(); s.points.forEach((p, i) => i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)); ctx.stroke() })
    this.texts.forEach(t => { ctx.fillStyle = t.color; ctx.font = `bold ${t.size}px sans-serif`; ctx.fillText(t.text, t.x, t.y) })
  },
  toXY(e) { const t = (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]); const r = e.currentTarget; return { x: t.x, y: t.y, dataset: r && r.dataset } },
  hitText(x, y) { if (!this.ctx) return null; for (let i = this.texts.length - 1; i >= 0; i--) { const t = this.texts[i]; this.ctx.font = `bold ${t.size}px sans-serif`; const w = this.ctx.measureText(t.text).width; if (x >= t.x - 8 && x <= t.x + w + 8 && y >= t.y - t.size - 4 && y <= t.y + 8) return t } return null },
  touchStart(e) {
    const p = e.touches[0]
    const hit = this.hitText(p.x, p.y)
    if (hit) { this.drag = { t: hit, ox: p.x - hit.x, oy: p.y - hit.y }; return }
    if (this.data.tool === 'text') { if (this.placingText) { this.texts.push({ text: this.placingText, x: p.x, y: p.y, color: this.data.color, size: 22 }); this.placingText = ''; this.setData({ textHint: '' }); this.redraw() } return }
    this.cur = { color: this.data.color, size: 4, points: [{ x: p.x, y: p.y }] }
  },
  touchMove(e) {
    const p = e.touches[0]
    if (this.drag) { this.drag.t.x = p.x - this.drag.ox; this.drag.t.y = p.y - this.drag.oy; this.redraw(); return }
    if (!this.cur) return; this.cur.points.push({ x: p.x, y: p.y }); this.redraw(); const ctx = this.ctx; ctx.strokeStyle = this.cur.color; ctx.lineWidth = this.cur.size; ctx.beginPath(); this.cur.points.forEach((pt, i) => i ? ctx.lineTo(pt.x, pt.y) : ctx.moveTo(pt.x, pt.y)); ctx.stroke()
  },
  touchEnd() { if (this.drag) { this.drag = null; return } if (this.cur) { this.strokes.push(this.cur); this.cur = null; this.redraw() } },
  pickTool(e) { this.setData({ tool: e.currentTarget.dataset.tool }); if (e.currentTarget.dataset.tool === 'text') this.askText(); else this.placingText = '' },
  pickColor(e) { this.setData({ color: e.currentTarget.dataset.color }) },
  askText() { wx.showModal({ title: '文字标注', editable: true, placeholderText: '输入批注文字', success: r => { if (r.confirm && r.content) { this.placingText = r.content; this.setData({ textHint: '点击画布放置文字；放置后可拖动调整位置' }) } else { this.setData({ tool: 'pen' }) } } }) },
  undo() { if (this.data.tool === 'text' && this.placingText) { this.placingText = ''; this.setData({ textHint: '' }); return } if (this.strokes.length) this.strokes.pop(); else if (this.texts.length) this.texts.pop(); this.redraw() },
  clear() { this.strokes = []; this.texts = []; this.redraw() },
  save() {
    if (!this.node) return
    wx.canvasToTempFilePath({ canvas: this.node, success: r => { this.getOpenerEventChannel().emit('saved', { tempPath: r.tempFilePath }); wx.navigateBack() }, fail: () => wx.showToast({ title: '导出失败', icon: 'none' }) })
  }
})
