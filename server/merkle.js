import { createHash } from 'node:crypto';

export function leaf(record, sig) {
  return createHash('sha256')
    .update(Buffer.concat([Buffer.from([0x00]), Buffer.from(`${record}|${sig}`, 'utf8')]))
    .digest();
}

export function root(leaves) {
  if (leaves.length === 0) return Buffer.alloc(32);
  let level = leaves.map(l => Buffer.isBuffer(l) ? l : Buffer.from(l, 'hex'));
  while (level.length > 1) {
    const next = [];
    for (let i = 0; i < level.length; i += 2) {
      if (i + 1 === level.length) { next.push(level[i]); continue; }
      next.push(createHash('sha256')
        .update(Buffer.concat([Buffer.from([0x01]), level[i], level[i + 1]]))
        .digest());
    }
    level = next;
  }
  return level[0];
}
