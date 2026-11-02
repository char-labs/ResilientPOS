// S6 준비. 점주·매장·단말·메뉴를 한 번 만들고 측정 실행이 읽을 파일(PREPARED)에 남긴다.
// 단말 토큰은 발급할 때만 받을 수 있어(해시만 저장) 측정마다 새로 만들면 시드와 매장이 어긋난다.
import { createMenu, provisionTerminals } from './lib/api.js';

const STORES = Number(__ENV.STORES || 250);
const PREPARED = __ENV.PREPARED || 'load-test/results/s6-prepared.json';

export const options = { setupTimeout: '10m', iterations: 1, vus: 1 };

export function setup() {
  const { terminals, credentials, session } = provisionTerminals(STORES, true);
  const storeIds = [...new Set(terminals.map((t) => t.storeId))];
  const menuEtags = Object.fromEntries(storeIds.map((storeId) => [storeId, createMenu(session, storeId)]));
  return { terminals, credentials, menuEtags };
}

export default function () {}

export function handleSummary(data) {
  return { [PREPARED]: JSON.stringify(data.setup_data) };
}
