// Reads a locally captured website script; evaluates ONLY its two pure curve functions.
// No DOM, advertising, analytics or website application code is executed.
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync('.research/inline.js', 'utf8');
const end = source.indexOf('const E=document');
if (!source.startsWith('function z(') || end < 0 || !source.includes('Math.round((Math.sqrt(5)-1)/2*d)')) {
  throw new Error('Website code changed: review the curve and shift before regenerating.');
}
const scope = vm.createContext({});
vm.runInContext(source.slice(0, end), scope, {timeout: 2000});
const sizes = [[1,1],[1,13],[17,1],[2,3],[3,2],[3,3],[4,5],[5,4],[7,11],[11,7],
  [16,16],[31,17],[17,31],[64,48],[48,64],[127,83]];
fs.mkdirSync('core/src/test/resources', {recursive:true});
const rows = sizes.map(([w,h]) => {
  const coords = vm.runInContext(`z(${w},${h})`, scope, {timeout:2000});
  const n=w*h, shift=Math.round((Math.sqrt(5)-1)/2*n), enc=new Array(n), dec=new Array(n);
  coords.forEach(([x,y],i)=>{
    const [tx,ty]=coords[(i+shift)%n];
    enc[ty*w+tx]=y*w+x; dec[y*w+x]=ty*w+tx;
  });
  return `${w}\t${h}\t${enc.join(',')}\t${dec.join(',')}`;
});
fs.writeFileSync('core/src/test/resources/website-vectors.tsv', rows.join('\n')+'\n');
const report={capturedAt:'2026-09-12',url:'https://xiaofanqiehunxiao.com/',
  scriptSha256:crypto.createHash('sha256').update(source).digest('hex'),cases:sizes,
  scope:'Website curve functions and pixel shift before JPEG encoding. Browser/Android codecs excluded.'};
fs.writeFileSync('docs/reference-capture.json',JSON.stringify(report,null,2)+'\n');
console.log(`Generated ${sizes.length} website vectors.`);
