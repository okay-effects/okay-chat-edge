// a page's socket, checked: opened on a conversation, a message POSTed, its turn pushed back `{n, turn}` with the reply
const [base, id] = process.argv.slice(2);
const ws = new WebSocket(base.replace(/^http/, 'ws') + `/c/${id}/socket`);
const got = new Promise((ok, no) => { ws.onmessage = e => ok(e.data); setTimeout(() => no(new Error('nothing pushed')), 8000); });
await new Promise((ok, no) => { ws.onopen = ok; ws.onerror = () => no(new Error('not opened')); });
await fetch(`${base}/c/${id}`, { method: 'POST', body: 'помощь' });
console.log(await got);
ws.close();
