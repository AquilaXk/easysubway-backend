#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";

const defaultFixture = "tools/routes/algorithm-fixtures/route-v2-fixtures.json";

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const fixtures = await loadFixtures(process.argv[2] ?? defaultFixture);
    console.log(JSON.stringify(runAll(fixtures), null, 2));
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

export async function loadFixtures(filePath = defaultFixture) {
  return normalize(JSON.parse(await readFile(path.resolve(filePath), "utf8")));
}

export function runAll(fixtures) {
  return {
    schemaVersion: 1,
    fixtureCount: fixtures.queries.length,
    results: fixtures.queries.map((query) => ({
      queryId: query.id,
      raptor: runRangeRaptor(fixtures, query)[0] ?? null,
      timeDependentDijkstra: runTimeDependentDijkstra(fixtures, query)[0] ?? null,
    })),
  };
}

export function runRangeRaptor(fixtures, query) {
  const start = departureMinute(query);
  const labelsByRound = [new Map([[query.origin, [label(query.origin, start)]]])];

  for (let round = 0; round <= maxTransfers(query); round += 1) {
    const current = cloneLabels(labelsByRound[round] ?? new Map());
    scanTrips(fixtures, query, current, round);
    scanTransfers(fixtures, query, current);
    labelsByRound[round + 1] = mergeMaps(labelsByRound[round + 1], current, fixtures.paretoLimit);
  }

  return alternatives(labelsByRound.at(-1)?.get(query.destination) ?? [], fixtures.paretoLimit);
}

export function runTimeDependentDijkstra(fixtures, query) {
  const start = departureMinute(query);
  let queue = [label(query.origin, start)];
  const best = new Map([[query.origin, queue]]);

  while (queue.length > 0) {
    queue.sort(compareLabels);
    const current = queue.shift();
    for (const next of nextLabels(fixtures, query, current)) {
      const stationLabels = best.get(next.station) ?? [];
      if (stationLabels.some((candidate) => sameLabel(candidate, next) || dominates(candidate, next))) continue;
      const kept = keepPareto([...stationLabels.filter((candidate) => !dominates(next, candidate)), next], fixtures.paretoLimit);
      best.set(next.station, kept);
      queue.push(next);
    }
  }

  return alternatives(best.get(query.destination) ?? [], fixtures.paretoLimit);
}

function normalize(fixtures) {
  return {
    ...fixtures,
    paretoLimit: fixtures.paretoLimit ?? 3,
    transferSlackMinutes: Math.ceil((fixtures.transferSlackSeconds ?? 0) / 60),
    transfers: fixtures.transfers ?? [],
    trips: fixtures.trips.map((trip) => ({
      ...trip,
      stops: trip.stops.map(([station, arrival, departure], index) => ({
        station,
        arrival: parseClock(arrival),
        departure: parseClock(departure),
        index,
      })),
    })),
  };
}

function scanTrips(fixtures, query, labels, round) {
  // ponytail: fixture scan only; production RAPTOR should index trips by route and marked stops.
  for (const trip of fixtures.trips) {
    if (!isServiceActive(fixtures, query, trip) || trip.realtime === "stale") continue;
    let boarded = null;
    for (const stop of trip.stops) {
      for (const previous of labels.get(stop.station) ?? []) {
        if (previous.boardings === round && canBoard(fixtures, query, previous, stop)) {
          boarded = betterBoarding(boarded, previous, stop);
        }
      }
      if (!boarded || stop.index <= boarded.stop.index) continue;
      addLabel(labels, {
        station: stop.station,
        time: stop.arrival,
        boardings: boarded.label.boardings + 1,
        path: [...boarded.label.path, leg(trip, boarded.stop, stop)],
      }, fixtures.paretoLimit);
    }
  }
}

function scanTransfers(fixtures, query, labels) {
  let changed = true;
  while (changed) {
    changed = false;
    for (const transfer of fixtures.transfers) {
      if (query.constraintMode === "STRICT_STEP_FREE" && (!transfer.stepFree || !transfer.verified)) continue;
      for (const previous of labels.get(transfer.from) ?? []) {
        changed = addLabel(labels, {
          station: transfer.to,
          time: previous.time + Math.ceil(transfer.durationSeconds / 60),
          boardings: previous.boardings,
          path: [...previous.path, { type: "transfer", from: transfer.from, to: transfer.to, durationSeconds: transfer.durationSeconds }],
        }, fixtures.paretoLimit) || changed;
      }
    }
  }
}

function nextLabels(fixtures, query, current) {
  const next = [];
  for (const transfer of fixtures.transfers) {
    if (transfer.from !== current.station) continue;
    if (query.constraintMode === "STRICT_STEP_FREE" && (!transfer.stepFree || !transfer.verified)) continue;
    next.push({
      station: transfer.to,
      time: current.time + Math.ceil(transfer.durationSeconds / 60),
      boardings: current.boardings,
      path: [...current.path, { type: "transfer", from: transfer.from, to: transfer.to, durationSeconds: transfer.durationSeconds }],
    });
  }

  if (current.boardings >= maxTransfers(query) + 1) return next;
  for (const trip of fixtures.trips) {
    if (!isServiceActive(fixtures, query, trip) || trip.realtime === "stale") continue;
    const from = trip.stops.find((stop) => stop.station === current.station && canBoard(fixtures, query, current, stop));
    if (!from) continue;
    for (const to of trip.stops.slice(from.index + 1)) {
      next.push({
        station: to.station,
        time: to.arrival,
        boardings: current.boardings + 1,
        path: [...current.path, leg(trip, from, to)],
      });
    }
  }
  return next;
}

function label(station, time) {
  return { station, time, boardings: 0, path: [] };
}

function leg(trip, from, to) {
  return {
    type: "ride",
    tripId: trip.id,
    lineId: trip.lineId,
    pattern: trip.pattern,
    from: from.station,
    to: to.station,
    departure: formatClock(from.departure),
    arrival: formatClock(to.arrival),
  };
}

function addLabel(labels, candidate, limit) {
  const stationLabels = labels.get(candidate.station) ?? [];
  if (stationLabels.some((existing) => sameLabel(existing, candidate) || dominates(existing, candidate))) return false;
  labels.set(candidate.station, keepPareto([
    ...stationLabels.filter((existing) => !dominates(candidate, existing)),
    candidate,
  ], limit));
  return true;
}

function alternatives(labels, limit) {
  return keepPareto(labels, limit).map((result) => ({
    arrival: formatClock(result.time),
    durationSeconds: (result.time - (result.path[0]?.departure ? parseClock(result.path[0].departure) : result.time)) * 60,
    transferCount: Math.max(0, result.boardings - 1),
    tripIds: result.path.filter((step) => step.type === "ride").map((step) => step.tripId),
    path: result.path,
  }));
}

function keepPareto(labels, limit) {
  return labels
    .filter((candidate, index, all) => !all.some((other, otherIndex) => otherIndex !== index && dominates(other, candidate)))
    .sort(compareLabels)
    .slice(0, limit);
}

function dominates(left, right) {
  return left.time <= right.time && left.boardings <= right.boardings && (left.time < right.time || left.boardings < right.boardings);
}

function sameLabel(left, right) {
  return left.time === right.time
    && left.boardings === right.boardings
    && left.path.map((step) => step.tripId ?? `${step.from}->${step.to}`).join("|")
      === right.path.map((step) => step.tripId ?? `${step.from}->${step.to}`).join("|");
}

function compareLabels(left, right) {
  return left.time - right.time || left.boardings - right.boardings || left.path.length - right.path.length;
}

function canBoard(fixtures, query, previous, stop) {
  const entrySlack = Math.ceil((query.entrySlackSeconds ?? 0) / 60);
  const slack = previous.path.length === 0 ? entrySlack : fixtures.transferSlackMinutes;
  return stop.departure >= previous.time + slack;
}

function betterBoarding(current, label, stop) {
  if (!current) return { label, stop };
  return label.time < current.label.time ? { label, stop } : current;
}

function maxTransfers(query) {
  return query.maxTransfers ?? 3;
}

function departureMinute(query) {
  return parseClock(query.departure.slice(11, 16));
}

function parseClock(clock) {
  const [hours, minutes] = clock.split(":").map(Number);
  return hours * 60 + minutes;
}

function formatClock(minutes) {
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function isServiceActive(fixtures, query, trip) {
  const serviceDate = query.departure.slice(0, 10);
  return !(fixtures.calendarDates?.[serviceDate] ?? []).includes(trip.service);
}

function cloneLabels(labels) {
  return new Map([...labels].map(([station, values]) => [station, [...values]]));
}

function mergeMaps(left = new Map(), right = new Map(), limit = 3) {
  const merged = cloneLabels(left);
  for (const [station, labels] of right) {
    merged.set(station, keepPareto([...(merged.get(station) ?? []), ...labels], limit));
  }
  return merged;
}
