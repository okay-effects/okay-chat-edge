// a stand-in for the Bot API in tests: every sendMessage appended to a file, one JSON per line
import { createServer } from 'node:http'
import { appendFileSync, writeFileSync } from 'node:fs'
const [port, file] = process.argv.slice(2)
writeFileSync(file, '')
createServer((req, res) => {
  let body = ''
  req.on('data', c => body += c)
  req.on('end', () => {
    appendFileSync(file, JSON.stringify({ path: req.url.replace(/bot[^/]+/, 'bot…'), body: JSON.parse(body || '{}') }) + '\n')
    res.setHeader('content-type', 'application/json')
    res.end('{"ok":true,"result":{}}')
  })
}).listen(Number(port))
