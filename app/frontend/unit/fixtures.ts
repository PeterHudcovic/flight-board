import { clockText, type Board, type Flight } from '../src/model';
export const NOW = Date.parse('2030-01-15T22:40:00Z');
export function makeFlight(index = 0): Flight {
  const scheduledAt = new Date(NOW + index * 15 * 60_000).toISOString();
  return { number: 'ZZ' + (1200 + index), scheduledAt, scheduled: clockText(Date.parse(scheduledAt)).slice(0, 5), expected: '',
    destination: ['EXAMPLEVILLE', 'NORTH HAVEN', 'PORT EXAMPLE'][index % 3]!, checkIn: '100-102',
    bagDrop: '', remark: index === 0 ? 'Cancelled' : index === 1 ? 'Boarding' : '',
    remarkColor: index === 0 ? 'RED' : index === 1 ? 'YELLOW' : 'WHITE', terminal: '2' };
}
export function makeBoard(overrides: Partial<Board> = {}): Board {
  return { flights: [makeFlight()], publishedAt: new Date(NOW).toISOString(),
    dataAgeSeconds: 0, stale: false, runId: 'synthetic-run', ...overrides };
}
