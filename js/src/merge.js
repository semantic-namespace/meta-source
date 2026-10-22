'use strict';
// What several sources say about the same entities, merged as
// meta-source.merge does: different keys from different sources combine; a
// key given twice with the same value is fine; any other key given with
// different values is a conflict, reported with every source that gave it,
// and left out of the entity; a key whose policy is :union takes the union
// of every source's set.
const { EdnSet, equal, itemsOf, prStr } = require('./edn');

const label = (s) => prStr(new Map([['file', s.file], ['line', s.line]]));
const sortSources = (xs) => [...new Map(xs.map((s) => [label(s), s])).values()].sort((a, b) => label(a).localeCompare(label(b)));

/**
 * sources: [{source: {file, line}, entities: Map(id -> Map(key -> value))}]
 * policy: Map(key -> ':union')
 * -> {entities: Map(id -> Map), provenance: Map(id -> Map(key -> [source])), conflicts: [...]}
 */
function mergeSources(sources, policy = new Map()) {
  const byKey = new Map(); // id -> key -> [{v, source}]
  for (const { source, entities } of sources)
    for (const [id, entry] of entities) {
      if (!byKey.has(id)) byKey.set(id, new Map());
      const ks = byKey.get(id);
      if (entry instanceof Map)
        for (const [k, v] of entry) {
          if (!ks.has(k)) ks.set(k, []);
          ks.get(k).push({ v, source });
        }
    }
  const entities = new Map();
  const provenance = new Map();
  const conflicts = [];
  for (const [id, ks] of byKey) {
    const entry = new Map();
    const prov = new Map();
    for (const [k, gs] of ks) {
      prov.set(k, sortSources(gs.map((g) => g.source)));
      if (policy.get(k) === ':union') {
        entry.set(k, new EdnSet(gs.flatMap((g) => itemsOf(g.v))));
        continue;
      }
      const byValue = [];
      for (const g of gs) {
        const hit = byValue.find((b) => equal(b.value, g.v));
        if (hit) hit.sources.push(g.source);
        else byValue.push({ value: g.v, sources: [g.source] });
      }
      if (byValue.length === 1) entry.set(k, byValue[0].value);
      else
        conflicts.push({
          id, key: k,
          values: byValue
            .map((b) => ({ value: b.value, sources: sortSources(b.sources) }))
            .sort((a, b) => a.sources.map(label).join().localeCompare(b.sources.map(label).join())),
        });
    }
    entities.set(id, entry);
    provenance.set(id, prov);
  }
  conflicts.sort((a, b) => prStr([a.id, a.key]).localeCompare(prStr([b.id, b.key])));
  return { entities, provenance, conflicts };
}

module.exports = { mergeSources };
