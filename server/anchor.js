import { ethers } from 'ethers';
import { leaf, root } from './merkle.js';

const ABI = ['function anchor(bytes32,uint32) external', 'function anchoredAt(bytes32) view returns (uint64)'];
const BATCH_SECONDS = parseInt(process.env.BATCH_SECONDS || '300', 10);
const BATCH_MAX = parseInt(process.env.BATCH_MAX || '500', 10);
const CONFIRMATIONS = parseInt(process.env.CONFIRMATIONS || '20', 10);
const CHAIN_ID = parseInt(process.env.CHAIN_ID || '80002', 10);
// Polygon Amoy requires at least 30 Gwei priority fee
const MIN_PRIORITY = 30_000_000_000n;

let running = false;

function provider() { return new ethers.JsonRpcProvider(process.env.CHAIN_RPC_URL); }
async function contract(sign) {
  const p = provider();
  if (!sign) return new ethers.Contract(process.env.CONTRACT_ADDRESS, ABI, p);
  return new ethers.Contract(process.env.CONTRACT_ADDRESS, ABI, new ethers.Wallet(process.env.CHAIN_PRIVATE_KEY, p));
}

async function sendAnchor(rootHex, count) {
  const c = await contract(true);
  const fd = await c.runner.provider.getFeeData();
  const tx = await c.anchor('0x' + rootHex, count, {
    maxFeePerGas: fd.maxFeePerGas,
    maxPriorityFeePerGas: fd.maxPriorityFeePerGas > MIN_PRIORITY ? fd.maxPriorityFeePerGas : MIN_PRIORITY
  });
  return tx.wait(1).then(r => ({ hash: tx.hash, block: r.blockNumber }));
}

async function markAnchored(sb, batchId, rootHex, txHash, blockNumber) {
  const ts = await (await contract()).anchoredAt('0x' + rootHex);
  const anchAt = new Date(Number(ts) * 1000).toISOString();
  await sb.from('anchor_batches').update({ status: 3, tx_hash: txHash || null, block_number: blockNumber || null, anchored_at: anchAt }).eq('id', batchId);
  await sb.from('evidence').update({ status: 3 }).eq('batch_id', batchId);
}

async function resumePending(sb) {
  const { data } = await sb.from('anchor_batches').select('id,root,leaves').eq('status', 2);
  for (const b of data || []) {
    try {
      const onChain = await (await contract()).anchoredAt('0x' + b.root);
      if (onChain !== 0n) { await markAnchored(sb, b.id, b.root, null, null); continue; }
      const { hash, block } = await sendAnchor(b.root, b.leaves.length);
      await markAnchored(sb, b.id, b.root, hash, block);
    } catch (e) { console.error('resume error:', e.message); }
  }
}

async function buildBatch(sb) {
  const { data: rows } = await sb.from('evidence').select('id,record,sig').is('batch_id', null).order('received_at').order('id').limit(BATCH_MAX);
  if (!rows?.length) return;
  const leaves = rows.map(r => leaf(r.record, r.sig));
  const rootHex = root(leaves).toString('hex');
  const leavesHex = leaves.map(l => l.toString('hex'));
  const { data: batch, error } = await sb.from('anchor_batches').insert({ root: rootHex, leaves: leavesHex, chain_id: CHAIN_ID }).select('id').single();
  if (error) { console.error('batch insert:', error.message); return; }
  await sb.from('evidence').update({ batch_id: batch.id }).in('id', rows.map(r => r.id));
  try {
    const onChain = await (await contract()).anchoredAt('0x' + rootHex);
    if (onChain !== 0n) { await markAnchored(sb, batch.id, rootHex, null, null); return; }
    const { hash, block } = await sendAnchor(rootHex, rows.length);
    await markAnchored(sb, batch.id, rootHex, hash, block);
  } catch (e) { console.error('anchor error:', e.message); }
}

async function confirmBatches(sb) {
  const { data } = await sb.from('anchor_batches').select('id,block_number').eq('status', 3);
  if (!data?.length) return;
  const cur = await provider().getBlockNumber();
  for (const b of data) {
    if (b.block_number === null || cur - b.block_number >= CONFIRMATIONS) {
      await sb.from('anchor_batches').update({ status: 4 }).eq('id', b.id);
      await sb.from('evidence').update({ status: 4 }).eq('batch_id', b.id);
    }
  }
}

export function startAnchor(sb) {
  console.log(`anchor worker: batch every ${BATCH_SECONDS}s`);
  const tick = async () => {
    if (running) return;
    running = true;
    try { await resumePending(sb); await buildBatch(sb); await confirmBatches(sb); }
    catch (e) { console.error('tick:', e.message); }
    finally { running = false; }
  };
  setInterval(tick, BATCH_SECONDS * 1000);
}
