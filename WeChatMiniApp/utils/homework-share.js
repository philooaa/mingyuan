// Share only a public entry point; never include a teacher's or child's account context.
function card() {
  return {
    title: '名远教育｜老师布置作业啦，点击查看',
    path: '/pages/homework-share/homework-share',
    imageUrl: '/assets/homework-share.jpg'
  }
}
module.exports = { card }
