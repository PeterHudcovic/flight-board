import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BoardController, type StorageLike } from '../src/controller';
import { CACHE_KEY, MAX_AGE_MS, POLL_MS } from '../src/model';
import { makeBoard, NOW } from './fixtures';
class MemoryStorage implements StorageLike {
  values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}
const controllers: BoardController[] = [];
function create(storage: StorageLike | null, fetcher: typeof fetch) {
  const controller = new BoardController(storage, fetcher);
  controllers.push(controller); return controller;
}
const response = (payload: unknown, status = 200) => new Response(JSON.stringify(payload), { status });
async function settle() { await vi.advanceTimersByTimeAsync(0); }
beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(NOW); });
afterEach(() => { controllers.forEach(controller => controller.stop()); controllers.length = 0; vi.useRealTimers(); });
describe('board lifecycle', () => {
  it('loads immediately then every 30 seconds with no-store, and keeps original publication time', async () => {
    const storage = new MemoryStorage();
    const request = vi.fn<typeof fetch>().mockImplementation(async () => response(makeBoard()));
    const controller = create(storage, request); controller.start(); await settle();
    expect(request).toHaveBeenCalledWith('/api/departures', expect.objectContaining({ cache: 'no-store' }));
    expect(controller.getSnapshot().connection).toBe('online');
    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(request).toHaveBeenCalledTimes(2);
    expect(JSON.parse(storage.getItem(CACHE_KEY)!)).toEqual(makeBoard());
    expect(controller.getSnapshot().now).toBe(NOW + POLL_MS);
  });
  it('uses persisted cache during an API outage and recovers on the next check', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const request = vi.fn<typeof fetch>().mockRejectedValueOnce(new Error('offline')).mockImplementation(async () => response(makeBoard({ runId: 'recovered', publishedAt: new Date(NOW + 1000).toISOString() })));
    const controller = create(storage, request); controller.start(); await settle();
    expect(controller.getSnapshot().connection).toBe('offline');
    expect(controller.getSnapshot().board?.runId).toBe('synthetic-run');
    await vi.advanceTimersByTimeAsync(POLL_MS);
    expect(controller.getSnapshot().connection).toBe('online');
    expect(controller.getSnapshot().board?.runId).toBe('recovered');
  });
  it('shows no board on first-visit outage', async () => {
    const controller = create(new MemoryStorage(), vi.fn<typeof fetch>().mockRejectedValue(new Error('offline')));
    controller.start(); await settle(); expect(controller.getSnapshot()).toMatchObject({ board: null, connection: 'offline' });
  });
  it('replaces old flights with a valid empty success and persists the empty board', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response(makeBoard({ flights: [] }))));
    controller.start(); await settle();
    expect(controller.getSnapshot().board?.flights).toEqual([]);
    expect(JSON.parse(storage.getItem(CACHE_KEY)!).flights).toEqual([]);
  });
  it('NO_DATA clears the previous board and cache instead of showing an obsolete copy', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response({ code: 'NO_DATA' }, 503)));
    controller.start(); await settle();
    expect(controller.getSnapshot()).toMatchObject({ board: null, connection: 'no-data' });
    expect(storage.getItem(CACHE_KEY)).toBeNull();
  });
  it.each([502, 503, 429])('keeps a usable copy for HTTP %s other than NO_DATA', async status => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response({ code: 'DATABASE_UNAVAILABLE' }, status)));
    controller.start(); await settle();
    expect(controller.getSnapshot()).toMatchObject({ connection: 'offline', board: makeBoard() });
  });
  it.each(['{', '{}', JSON.stringify(makeBoard({ publishedAt: new Date(NOW - MAX_AGE_MS - 1).toISOString() }))])('deletes invalid/expired persisted data %#', value => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, value);
    expect(create(storage, fetch).getSnapshot().board).toBeNull();
    expect(storage.getItem(CACHE_KEY)).toBeNull();
  });
  it('expires a cached copy while the page runs even with repeated connection failures', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard({ publishedAt: new Date(NOW - MAX_AGE_MS + 2000).toISOString() })));
    const controller = create(storage, vi.fn<typeof fetch>().mockRejectedValue(new Error('offline')));
    controller.start(); await settle(); expect(controller.getSnapshot().board).not.toBeNull();
    await vi.advanceTimersByTimeAsync(3000);
    expect(controller.getSnapshot().board).toBeNull(); expect(storage.getItem(CACHE_KEY)).toBeNull();
  });
  it('rejects a malformed API response without destroying the last valid board', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response({ flights: ['bad'] })));
    controller.start(); await settle(); expect(controller.getSnapshot()).toMatchObject({ connection: 'offline', board: makeBoard() });
  });
  it('ignores a response from a superseded request even when cancellation is ignored', async () => {
    let first!: (value: Response) => void;
    const request = vi.fn<typeof fetch>().mockImplementationOnce(() => new Promise(resolve => { first = resolve; }))
      .mockResolvedValueOnce(response(makeBoard({ runId: 'newer', publishedAt: new Date(NOW + 1000).toISOString() })));
    const controller = create(new MemoryStorage(), request); controller.start();
    await controller.refresh();
    first(response(makeBoard())); await settle();
    expect(controller.getSnapshot().board?.runId).toBe('newer');
  });
  it('an older publication cannot replace or expire a newer cached publication', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response(makeBoard({
      publishedAt: new Date(NOW - MAX_AGE_MS - 1000).toISOString(), runId: 'older', dataAgeSeconds: 50_000,
    }))));
    controller.start(); await settle(); expect(controller.getSnapshot().board?.runId).toBe('synthetic-run');
    expect(JSON.parse(storage.getItem(CACHE_KEY)!).runId).toBe('synthetic-run');
  });
  it('does not regress a newer copy written by another tab', async () => {
    const storage = new MemoryStorage(); storage.setItem(CACHE_KEY, JSON.stringify(makeBoard()));
    const controller = create(storage, vi.fn<typeof fetch>().mockResolvedValue(response(makeBoard())));
    const newer = makeBoard({ runId: 'other-tab', publishedAt: new Date(NOW + 1000).toISOString() });
    storage.setItem(CACHE_KEY, JSON.stringify(newer));
    controller.start(); await settle();
    expect(controller.getSnapshot().board?.runId).toBe('other-tab');
    expect(JSON.parse(storage.getItem(CACHE_KEY)!).runId).toBe('other-tab');
  });
  it('expiration in one tab does not delete a newer cache from another tab', async () => {
    const storage = new MemoryStorage();
    storage.setItem(CACHE_KEY, JSON.stringify(makeBoard({ publishedAt: new Date(NOW - MAX_AGE_MS + 2000).toISOString() })));
    const controller = create(storage, vi.fn<typeof fetch>().mockImplementation(() => new Promise(() => {})));
    controller.start();
    storage.setItem(CACHE_KEY, JSON.stringify(makeBoard({ runId: 'newer-other-tab' })));
    await vi.advanceTimersByTimeAsync(3000);
    expect(controller.getSnapshot().board?.runId).toBe('newer-other-tab');
    expect(JSON.parse(storage.getItem(CACHE_KEY)!).runId).toBe('newer-other-tab');
  });
  it('does not crash when all localStorage methods throw', async () => {
    const denied = { getItem() { throw Error('denied'); }, setItem() { throw Error('denied'); }, removeItem() { throw Error('denied'); } };
    const controller = create(denied, vi.fn<typeof fetch>().mockResolvedValue(response(makeBoard())));
    controller.start(); await settle(); expect(controller.getSnapshot().board).toEqual(makeBoard());
  });
  it('times out hanging requests and remains available for the next polling attempt', async () => {
    const request = vi.fn<typeof fetch>().mockImplementationOnce((_url, init) => new Promise((_resolve, reject) =>
      init?.signal?.addEventListener('abort', () => reject(Error('timeout')))))
      .mockResolvedValueOnce(response(makeBoard()));
    const controller = create(null, request); controller.start();
    await vi.advanceTimersByTimeAsync(10_000); expect(controller.getSnapshot().connection).toBe('offline');
    await vi.advanceTimersByTimeAsync(20_000); expect(controller.getSnapshot().connection).toBe('online');
  });
  it('stop cancels timers and ignores an in-flight result', async () => {
    let finish!: (response: Response) => void;
    const request = vi.fn<typeof fetch>().mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const controller = create(null, request); controller.start(); controller.stop();
    finish(response(makeBoard())); await vi.advanceTimersByTimeAsync(60_000);
    expect(request).toHaveBeenCalledTimes(1); expect(controller.getSnapshot().board).toBeNull();
  });
});
