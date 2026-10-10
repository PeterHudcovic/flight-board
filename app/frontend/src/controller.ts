import { CACHE_KEY, MAX_AGE_MS, POLL_MS, REQUEST_TIMEOUT_MS, parseBoard, usable, type Board } from './model';

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}
export type Connection = 'checking' | 'online' | 'offline' | 'no-data';
export interface Snapshot { board: Board | null; connection: Connection; now: number }
export class BoardController {
  private snapshot: Snapshot;
  private listeners = new Set<() => void>();
  private polling?: ReturnType<typeof setInterval>;
  private ticking?: ReturnType<typeof setInterval>;
  private active?: AbortController;
  private generation = 0;
  private stopped = true;
  constructor(private storage: StorageLike | null, private request: typeof fetch = (input, init) => fetch(input, init), private now: () => number = Date.now) {
    const time = now();
    let board: Board | null = null;
    try {
      const raw = storage?.getItem(CACHE_KEY);
      board = raw ? parseBoard(JSON.parse(raw)) : null;
      if (board && !usable(board, time)) board = null;
      if (raw && !board) this.clearCache();
    } catch { this.clearCache(); }
    this.snapshot = { board, connection: 'checking', now: time };
  }
  getSnapshot = (): Snapshot => this.snapshot;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  private update(board: Board | null, connection: Connection, time = this.now()) {
    this.snapshot = { board, connection, now: time };
    this.listeners.forEach(listener => listener());
  }
  private clearCache() {
    try { this.storage?.removeItem(CACHE_KEY); } catch { /* Rendering works when storage is disabled. */ }
  }
  private save(board: Board) {
    try { this.storage?.setItem(CACHE_KEY, JSON.stringify(board)); } catch { /* Quota/privacy settings must not break the board. */ }
  }
  private newestKnownBoard(): Board | null {
    const current = this.snapshot.board;
    try {
      const raw = this.storage?.getItem(CACHE_KEY);
      const persisted = raw ? parseBoard(JSON.parse(raw)) : null;
      if (persisted && usable(persisted, this.now()) && (!current || Date.parse(persisted.publishedAt) > Date.parse(current.publishedAt))) return persisted;
    } catch { /* Storage may be disabled or modified by another tab. */ }
    return current && usable(current, this.now()) ? current : null;
  }
  start() {
    if (!this.stopped) return;
    this.stopped = false;
    void this.refresh();
    this.polling = setInterval(() => { void this.refresh(); }, POLL_MS);
    this.ticking = setInterval(() => {
      const { connection } = this.snapshot;
      const board = this.newestKnownBoard();
      const time = this.now();
      if (!board && this.snapshot.board) {
        this.clearCache();
        this.update(null, connection === 'online' ? 'no-data' : connection, time);
      } else this.update(board, connection, time);
    }, 1000);
  }
  stop() {
    this.stopped = true;
    this.generation++;
    this.active?.abort();
    clearInterval(this.polling); clearInterval(this.ticking);
  }
  async refresh(): Promise<void> {
    if (this.stopped) return;
    this.active?.abort();
    const abort = new AbortController();
    this.active = abort;
    const generation = ++this.generation;
    const timeout = setTimeout(() => abort.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await this.request('/api/departures', { signal: abort.signal, cache: 'no-store', headers: { Accept: 'application/json' } });
      const payload: unknown = await response.json();
      if (this.stopped || generation !== this.generation) return;
      if (response.status === 503 && typeof payload === 'object' && payload !== null && 'code' in payload && payload.code === 'NO_DATA') {
        this.clearCache(); this.update(null, 'no-data'); return;
      }
      if (!response.ok) throw new Error('API unavailable');
      const board = parseBoard(payload);
      if (!board) throw new Error('Invalid board');
      const previous = this.newestKnownBoard();
      // Original publication time determines freshness; arrival order never does.
      if (previous && Date.parse(board.publishedAt) < Date.parse(previous.publishedAt)) {
        this.update(previous, 'online'); return;
      }
      if (!usable(board, this.now()) || board.dataAgeSeconds * 1000 > MAX_AGE_MS) {
        this.clearCache(); this.update(null, 'no-data'); return;
      }
      this.save(board); this.update(board, 'online');
    } catch {
      if (this.stopped || generation !== this.generation) return;
      const board = this.newestKnownBoard();
      if (!board) this.clearCache();
      this.update(board && usable(board, this.now()) ? board : null, 'offline');
    } finally { clearTimeout(timeout); }
  }
}
export function browserStorage(): StorageLike | null {
  try { return window.localStorage; } catch { return null; }
}
