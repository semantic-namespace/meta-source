# conformance

The answers every meta-source reader must give about a registry.

`polyglot/` is one registry implemented in two languages: `src/shop/price.clj`
(Clojure) and `web/` (TypeScript). `expected.json` holds the Clojure reader's
answers to the questions in `conformance.clj`, keeping only what is about
the registry: where an id is defined, its sources and merged entry, what
names it, what it names, a data key's producers and consumers, the
registry's problems, and a hover's kind and those locations. What a reader
finds in its own language's code (an implementing declaration, code calling
it, coverage) is left out; each reader's own tests check that.

```sh
bb conformance            # the Clojure reader against expected.json
bb conformance --write    # regenerate expected.json from it
npm test                  # the JavaScript reader against expected.json
```
