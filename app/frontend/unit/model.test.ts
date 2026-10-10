import { describe, expect, it } from 'vitest';
import { MAX_AGE_MS, ageSeconds, clockText, orderedFlights, parseBoard, publishedText, usable } from '../src/model';
import { makeBoard, makeFlight, NOW } from './fixtures';
describe('API/cache schema', () => {
  it('accepts the PR16 board and a valid empty board', () => {
    expect(parseBoard(makeBoard())).toEqual(makeBoard());
    expect(parseBoard(makeBoard({ flights: [] }))?.flights).toEqual([]);
  });
  it.each([null, {}, { flights: [] }, makeBoard({ publishedAt: 'not-a-date' }),
    makeBoard({ publishedAt: '2030-02-30T10:00:00Z' }), makeBoard({ publishedAt: '2030-01-15T24:00:00Z' }),
    makeBoard({ stale: 'false' as never }), makeBoard({ dataAgeSeconds: -1 }),
    makeBoard({ flights: [{ ...makeFlight(), scheduledAt: 'bad' }] }),
    makeBoard({ flights: [{ ...makeFlight(), scheduled: '25:99' }] }),
    makeBoard({ flights: [{ ...makeFlight(), remarkColor: 'YELLOW' }] }),
    makeBoard({ flights: [{ ...makeFlight(), bagDrop: 'invented' }] }),
    makeBoard({ flights: [{ ...makeFlight(), number: '' }] })])('rejects malformed whole payload %#', value => {
    expect(parseBoard(value)).toBeNull();
  });
  it('removes unknown fields rather than caching provider details', () => {
    const board = { ...makeBoard(), extra: 'private', flights: [{ ...makeFlight(), raw: 'private' }] };
    expect(JSON.stringify(parseBoard(board))).not.toContain('private');
  });
});
describe('publication time and selection', () => {
  it('expires only after twelve hours and rejects implausible future publications', () => {
    expect(usable(makeBoard(), NOW + MAX_AGE_MS)).toBe(true);
    expect(usable(makeBoard(), NOW + MAX_AGE_MS + 1)).toBe(false);
    expect(usable(makeBoard({ publishedAt: new Date(NOW + 120_000).toISOString() }), NOW)).toBe(false);
    expect(ageSeconds(makeBoard(), NOW + 90_000)).toBe(90);
  });
  it('sorts full instants across midnight and breaks ties by flight number', () => {
    const flights = [makeFlight(2), makeFlight(0), { ...makeFlight(0), number: 'AA100' }];
    expect(orderedFlights(makeBoard({ flights })).map(flight => flight.number)).toEqual(['AA100', 'ZZ1200', 'ZZ1202']);
  });
  it('keeps the first 36 without mutating the original batch', () => {
    const board = makeBoard({ flights: Array.from({ length: 40 }, (_, index) => makeFlight(39 - index)) });
    expect(orderedFlights(board)).toHaveLength(36);
    expect(orderedFlights(board)[0]?.number).toBe('ZZ1200');
    expect(board.flights[0]?.number).toBe('ZZ1239');
  });
  it('uses Prague time independently of the machine timezone, including DST', () => {
    expect(clockText(Date.parse('2030-01-15T23:40:05Z'))).toBe('00:40:05');
    expect(clockText(Date.parse('2030-07-15T23:40:05Z'))).toBe('01:40:05');
    expect(publishedText('2030-01-15T22:40:00Z')).toBe('23:40');
    expect(publishedText('2030-07-15T22:40:00Z')).toBe('00:40');
  });
});
