// a stand-in for the Bot API in tests: every sendMessage appended to a file, one JSON per line; and for Workable's
// search (/api/v1/jobs): two vacancies for "scala", none for anything else — the query kept in the file too
import { createServer } from 'node:http'
import { appendFileSync, writeFileSync } from 'node:fs'
const [port, file] = process.argv.slice(2)
writeFileSync(file, '')
const vacancies = [
  { title: 'Scala Engineer', company: { title: 'Acme' }, location: { city: 'Wrocław', countryName: 'Poland' },
    workplace: 'hybrid', url: 'https://jobs.workable.com/view/1/scala-engineer' },
  { title: 'Senior Scala Developer', company: { title: 'Beta' }, location: { countryName: 'Poland' },
    workplace: 'remote', url: 'https://jobs.workable.com/view/2/senior-scala-developer' }]
// an employer's board (Greenhouse's shape): one vacancy at first, a second from the second reading on — a new one
let boardReads = 0
const board = () => ({ jobs: [
  { id: 1, title: 'Scala Engineer', company_name: 'Gamma', location: { name: 'Wrocław, Poland' },
    absolute_url: 'https://job-boards.greenhouse.io/gamma/jobs/1' },
  ...(boardReads > 1 ? [{ id: 2, title: 'Senior Scala Engineer', company_name: 'Gamma',
    location: { name: 'Wrocław, Poland (Remote)' }, absolute_url: 'https://job-boards.greenhouse.io/gamma/jobs/2' }] : [])] })
createServer((req, res) => {
  if (req.url.startsWith('/board/')) {
    boardReads++
    res.setHeader('content-type', 'application/json')
    return res.end(JSON.stringify(board()))
  }
  if (req.url.startsWith('/api/v1/jobs')) {
    const q = new URL(req.url, 'http://x').searchParams
    appendFileSync(file, JSON.stringify({ jobs: Object.fromEntries(q) }) + '\n')
    const jobs = q.get('query') === 'scala' ? vacancies : []
    res.setHeader('content-type', 'application/json')
    return res.end(JSON.stringify({ totalSize: jobs.length, jobs }))
  }
  let body = ''
  req.on('data', c => body += c)
  req.on('end', () => {
    appendFileSync(file, JSON.stringify({ path: req.url.replace(/bot[^/]+/, 'bot…'), body: JSON.parse(body || '{}') }) + '\n')
    res.setHeader('content-type', 'application/json')
    res.end('{"ok":true,"result":{}}')
  })
}).listen(Number(port))
