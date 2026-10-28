const vm=require('node:vm'),fs=require('node:fs'),path=require('node:path')
module.exports=(wx,getApp)=>{const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../utils/child-context.js'),'utf8'),{module,wx,getApp});return module.exports}
