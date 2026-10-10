import { useEffect, useSyncExternalStore } from 'react';
import { BoardController, browserStorage, type Snapshot } from './controller';
import { clockText, orderedFlights, publishedText, type Flight } from './model';

import { boardConfig } from './boardConfig';
import { Destination } from './Destination';

const controller = new BoardController(browserStorage());
const columns = ['Sched.', 'Exp.', 'Destination', 'Flight', 'Bag Drop', 'Check-in', 'Remark'];
function Row({ flight }: { flight?: Flight }) {
  return <tr className="flight-row">
    <td>{flight?.scheduled}</td><td>{flight?.expected}</td>
    <Destination name={flight?.destination} />
    <td>{flight?.number}</td><td>{flight?.bagDrop}</td><td>{flight?.checkIn}</td>
    <td className={flight ? 'remark remark-' + flight.remarkColor.toLowerCase() : 'remark'}>{flight?.remark}</td>
  </tr>;
}
export function statusText({ board, connection }: Snapshot): string | null {
  if (connection === 'checking') return board ? 'Checking connection' : 'Loading flight information';
  if (!board) return 'Flight information is temporarily unavailable';
  if (connection === 'offline') return 'Connection lost';
  if (board.stale) return 'Information may not be up to date';
  return null;
}
export default function App() {
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  useEffect(() => { controller.start(); return () => controller.stop(); }, []);
  const flights = orderedFlights(state.board);
  const message = statusText(state);
  const title = boardConfig.airportName + ' (' + boardConfig.airportCode + ') · DEPARTURES · TERMINAL ' + boardConfig.terminal;
  return <main className="board" aria-label={boardConfig.airportName + ' departure board'}>
    <header className="board-header"><h1>{title}</h1><time className="clock" aria-label="Prague time">{clockText(state.now)}</time></header>
    <div className={'notice' + (message ? ' notice-visible' : '')} role="status" aria-live="polite">
      {message || '\u00a0'}
    </div>
    <div className="blocks">
      {[0, 1, 2].map(block => <table className="flight-block" key={block} aria-label={'Departures ' + (block * 12 + 1) + ' to ' + ((block + 1) * 12)}>
        <colgroup>{columns.map((column, index) => <col className={'column-' + index} key={column} />)}</colgroup>
        <thead><tr>{columns.map(column => <th scope="col" key={column} aria-label={column}>{column === 'Bag Drop' ? <><span className="compact-heading">Bag</span><span className="compact-heading">Drop</span></> : column}</th>)}</tr></thead>
        <tbody>{Array.from({ length: 12 }, (_, row) => <Row key={row} flight={flights[block * 12 + row]} />)}</tbody>
      </table>)}
    </div>
    {state.board && flights.length === 0 && <p className="empty-message">No departures in the next hours</p>}
    <footer className="board-footer">
      <span className="publication">{state.board
        ? 'Last update ' + publishedText(state.board.publishedAt)
        : '\u00a0'}</span>
      <a href="https://aerodatabox.com/" target="_blank" rel="noopener noreferrer">data: AeroDataBox</a>
    </footer>
  </main>;
}
