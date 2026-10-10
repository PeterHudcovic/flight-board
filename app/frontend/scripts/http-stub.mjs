import http from 'node:http';
http.createServer((request, response) => {
  response.setHeader('Content-Type', 'application/json');
  if (request.url === '/api/departures?probe=1') {
    response.end(JSON.stringify({ flights: [], publishedAt: new Date().toISOString(), dataAgeSeconds: 0, stale: false, runId: 'synthetic-container-run' }));
  } else if (request.url === '/api/status') {
    response.end(JSON.stringify({ version: 'synthetic-container-sha' }));
  } else { response.statusCode = 404; response.end('{"code":"NOT_FOUND"}'); }
}).listen(8080, '0.0.0.0');
