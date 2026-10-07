const fs = require('fs');
const src = fs.readFileSync('app/src/main/java/com/clipdown/app/clip/WebViewHtmlFetcher.kt', 'utf8');

function extractJs(fnName) {
  const i = src.indexOf(fnName);
  if (i < 0) return null;
  const start = src.indexOf('evaluateJavascript(', i);
  let end = src.indexOf('\n    }', start);
  if (end < 0) end = src.length;
  const seg = src.slice(start, end);
  const strs = seg.match(/"(?:[^"\\]|\\.)*"/g) || [];
  return strs.map(s => s.slice(1, -1)).join('');
}

let failed = 0;
for (const [name, marker] of [
  ['installPageHook', 'private fun installPageHook'],
]) {
  const js = extractJs(marker);
  if (!js) { console.log(name, 'NOT FOUND'); failed++; continue; }
  try { new Function(js); console.log(name, 'OK'); }
  catch (e) { console.log(name, 'ERR', e.message, '\n  JS=', js); failed++; }
}
process.exit(failed ? 1 : 0);
