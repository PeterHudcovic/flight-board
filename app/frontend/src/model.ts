export const MAX_AGE_MS = 12 * 60 * 60 * 1000;
export const CACHE_KEY = 'flight-board:board:v1';
export const POLL_MS = 30_000;
export const REQUEST_TIMEOUT_MS = 10_000;

export interface Flight {
  number: string;
  scheduledAt: string;
  scheduled: string;
  expected: string;
  destination: string;
  checkIn: string;
  bagDrop: string;
  remark: string;
  remarkColor: 'WHITE' | 'YELLOW' | 'RED';
  terminal: string;
}
export interface Board {
  flights: Flight[];
  publishedAt: string;
  dataAgeSeconds: number;
  stale: boolean;
  runId: string;
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function timestamp(value: unknown): value is string {
  return typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value.slice(0, 10) + 'T00:00:00Z').toISOString().slice(0, 10) === value.slice(0, 10);
}
function flight(value: unknown): value is Flight {
  if (!record(value) || !timestamp(value.scheduledAt)) return false;
  for (const key of ['number', 'scheduled', 'expected', 'destination', 'checkIn', 'bagDrop', 'remark', 'terminal']) {
    if (typeof value[key] !== 'string' || value[key].length > 500) return false;
  }
  const time = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
  const colours: Record<string, string> = { '': 'WHITE', Boarding: 'YELLOW', 'Gate closed': 'WHITE', Delayed: 'WHITE', Cancelled: 'RED' };
  return (value.number as string).trim().length > 0 && time.test(value.scheduled as string) &&
    (value.expected === '' || time.test(value.expected as string)) &&
    value.bagDrop === '' && Object.hasOwn(colours, value.remark as string) &&
    colours[value.remark as string] === value.remarkColor;
}
/** Validate complete API/cache payloads before allowing them to replace the displayed board. */
export function parseBoard(value: unknown): Board | null {
  if (!record(value) || !timestamp(value.publishedAt) ||
      typeof value.runId !== 'string' || !value.runId.trim() || value.runId.length > 200 ||
      typeof value.stale !== 'boolean' || typeof value.dataAgeSeconds !== 'number' ||
      !Number.isFinite(value.dataAgeSeconds) || value.dataAgeSeconds < 0 ||
      !Array.isArray(value.flights) || value.flights.length > 1000 || !value.flights.every(flight)) return null;
  // Copy known fields only; provider data and unexpected properties never reach storage.
  return { publishedAt: value.publishedAt, runId: value.runId, stale: value.stale,
    dataAgeSeconds: value.dataAgeSeconds, flights: value.flights.map(f => ({
      number: f.number, scheduledAt: f.scheduledAt, scheduled: f.scheduled, expected: f.expected,
      destination: f.destination, checkIn: f.checkIn, bagDrop: f.bagDrop,
      remark: f.remark, remarkColor: f.remarkColor, terminal: f.terminal,
    })) };
}
export function usable(board: Board, now: number): boolean {
  const age = now - Date.parse(board.publishedAt);
  return age >= -60_000 && age <= MAX_AGE_MS;
}
export function ageSeconds(board: Board, now: number): number {
  return Math.max(0, Math.floor((now - Date.parse(board.publishedAt)) / 1000));
}
export function orderedFlights(board: Board | null): Flight[] {
  return [...(board?.flights ?? [])].sort((a, b) =>
    Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt) || (a.number < b.number ? -1 : a.number > b.number ? 1 : 0)).slice(0, 36);
}
export function clockText(now: number): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Prague', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(now);
}
export function publishedText(value: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Prague', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(Date.parse(value));
}
