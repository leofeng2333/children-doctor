const fs = require('fs');
const path = require('path');

const srcDir = path.join(__dirname, '../src');
const distDir = path.join(__dirname, '../dist/esm');

if (!fs.existsSync(distDir)) {
  fs.mkdirSync(distDir, { recursive: true });
}

const files = ['definitions.ts', 'web.ts'];
files.forEach(file => {
  const src = path.join(srcDir, file);
  const dest = path.join(distDir, file);
  fs.copyFileSync(src, dest);
});
