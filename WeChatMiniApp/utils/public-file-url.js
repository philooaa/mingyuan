// Public upload paths only; private documents keep their authenticated download APIs.
module.exports = function publicFileUrl(value) {
  if (!value) return ''
  const path = String(value).trim(), base = getApp().globalData.baseUrl.replace(/\/+$/, '')
  if (/^(wxfile|file):\/\//i.test(path)) return path
  const api = base.match(/^(https?:\/\/)([^/]+)(\/.*)?$/i)
  const absolute = path.match(/^https?:\/\/([^/]+)(\/.*)?$/i)
  const prefix = api && api[3] || ''
  let relative = path
  if (absolute) {
    if (!api || absolute[1].toLowerCase() !== api[2].toLowerCase()) return path
    relative = absolute[2] || '/'
    if (!relative.startsWith('/profile/') && !(prefix && relative.startsWith(prefix + '/profile/'))) return path
  }
  if (prefix && relative.startsWith(prefix + '/profile/')) relative = relative.slice(prefix.length)
  return base + '/' + relative.replace(/^\/+/, '')
}
