# Coalesce

A schema-agnostic **entity resolution** engine in Java 21: given records from many systems with no
shared identifier, decide which of them refer to the same real-world thing.

> **Status: in progress.** The domain core is built and tested. Blocking, persistence, the REST API
> and the benchmark harness are not written yet. See [Current state](#current-state) — it is precise
> about what does and does not exist, because a README that overstates is worse than one that
> underclaims.

---

## The problem

The same real thing gets recorded in different places, differently every time, with nothing to tie
the records together. Three systems in a hospital group might hold:

| Source | Name | Date of birth | Address |
|---|---|---|---|
| Hospital A | Jonathan Smith | `02/03/1985` | 17 Oak Street |
| Clinic B | Jon Smyth | `03/02/1985` | Oak St 17 |
| Pharmacy C | J. Smith | `1985-03-02` | 17 Oak Str, Apt 3 |

Almost certainly one person. But the first name is shortened, the surname misspelled, the date
written three ways with two of them disagreeing about which number is the month, and the address
rearranged and abbreviated. **Not one field matches exactly**, so `WHERE name = name AND dob = dob`
finds nothing.

The stakes run in both directions. Fail to link these and a doctor sees a third of the man's history
and misses an allergy. Link him to a *different* Jonathan Smith and the doctor is reading someone
else's chart.

Where this matters in practice:

- **Fraud and KYC** — one person opening accounts as "Robert Chen", "Rob Chen" and "R. Chen" against
  three spellings of the same flat.
- **Patient matching** — the case above, across hospital, clinic and pharmacy systems.
- **Supplier catalogues** — "M6 Hex Bolt 30mm", "Bolt, hex, M6x30" and `HEXBLT-M6-030` being one
  product you are unknowingly buying at three prices.

The engine is deliberately domain-neutral: attributes and their comparators are configuration, not
code, so the same binary handles patients, account holders and bolts.

## How it works

**1. Compare each attribute with a comparator that understands how *that kind* of data goes wrong.**
Names get Jaro-Winkler, which charges a transposition as half an edit, so `Micheal`/`Michael` scores
highly. Addresses get token cosine, which ignores word order, so `17 Oak Street` and `Oak Street 17`
are identical. Dates get a comparator that treats `02/03` versus `03/02` as a locale artefact rather
than a month's difference.

**2. Combine the evidence into a score**, weighted by how much each attribute is worth — agreement on
a national insurance number is near-proof; agreement on the surname "Smith" is nearly nothing.

**3. Decide three ways, not two** — `MATCH`, `REVIEW`, or `NO_MATCH`. Forcing a binary verdict on a
genuinely borderline pair guarantees being wrong in one direction or the other, so uncertain pairs go
to a human queue instead of being guessed at.

**4. Group agreed matches into clusters** using union-find, so A–B and B–C put all three together
without A and C ever being compared.

## Design decisions worth explaining

### A missing value is not a mismatch

`AttributeComparator.compare` returns `OptionalDouble.empty()` when it cannot interpret an input,
never `0.0`. If one record has no middle name that is the *absence of evidence*, not evidence the two
people differ. Scoring blanks as zero systematically penalises sparse records — exactly the records
most in need of matching — so the scorer renormalises over the attributes actually present on both
sides.

This is the single most commonly botched detail in entity resolution.

### Transitive closure alone is not a correct policy

Union-find will happily chain links, and that is a trap:

```
A   Jon Smith   1985   17 Oak St     A–B plausible: same name, same year
B   Jon Smith   1985   22 Elm Rd     B–C plausible: same name, same address
C   Jon Smith   1962   22 Elm Rd
```

A and C share nothing but a common name — different birth year, different address. Yet the chain puts
them together. At scale, chains of individually-plausible links quietly collapse large parts of a
dataset into one cluster of thousands of unrelated people. Nothing crashes; the answers are just
confidently wrong.

`ClusterPolicy` therefore vets every candidate link against the cluster it would create, sampling
pairs across the merge boundary and requiring real cohesion before allowing it, with a size ceiling as
a backstop. Refusals carry a reason, because *"why didn't these two merge?"* is the question a data
steward asks daily.

Keeping this policy out of `UnionFind` is intentional: the graph algorithm stays a pure data
structure, and the judgement calls stay separately testable.

### Merges must be reversible

Every pairwise link is stored as a first-class record with its score, decision, and origin
(`AUTOMATIC` or `MANUAL_OVERRIDE`). Because clusters are built by transitive closure, retracting one
link means recomputing connected components for that cluster from the *surviving* links — which is
only possible because the individual links were kept rather than just the final grouping.

A human asserting "these two are **not** the same" is recorded as a negative link that later automatic
runs cannot override. Human judgement outranks the algorithm.

### Why the algorithms are hand-written

There is no `commons-text`, no `simmetrics`, no similarity library. Jaro-Winkler, Levenshtein, Soundex
and the MinHash signature are the substance of the project rather than incidental plumbing, and
"how does Jaro-Winkler handle transpositions" deserves an implementation as an answer, not a
dependency. The reference values published with each algorithm are asserted in the tests, so the
implementations are pinned to the real algorithm rather than to whatever this code happens to compute.

### The domain has no framework

Nothing under `com.coalesce.domain` imports Spring or JPA. Every algorithm is constructible with `new`
and testable with no application context, which is why the suite runs in about a second. Wiring lives
only in `config`, and persistence only in `infrastructure`.

## Comparators

| Comparator | For | Note |
|---|---|---|
| `jaro-winkler` | personal names | transposition-tolerant, prefix-weighted |
| `levenshtein` | codes and identifiers | normalised so scores compare across lengths |
| `soundex` | phonetic corroboration | coarse and anglocentric — see limitations |
| `token-cosine` | addresses, org names | word-order insensitive |
| `exact` | unique keys, emails | no partial credit by design |
| `numeric` | amounts, quantities | scale-free relative decay |
| `date` | dates of birth | day/month transposition allowance |

## Current state

**Built and tested** — 47 tests, all passing:

- All seven comparators, with published reference values asserted
- `UnionFind` — path halving plus union by rank, with an adversarial-merge-order test

**Written but not yet tested** (the next task):

- `PairScorer`, `ScoredPair`, `MatchDecision`, `CandidatePair`
- `ClusterPolicy`, `ClusterState`, `LinkVerdict`, `EntityCluster`
- `LinkLedger`, `EntityLink`, `UnmergeResult`, `SeparationResult`
- `ResolutionSchema`, and the repository ports

**Not started:**

- MinHash/LSH and sorted-neighborhood blocking, so candidate generation is still O(n²)
- Persistence adapters and the Flyway migration — **the app will not start yet**
- REST API
- Precision/recall/F1 benchmark harness (the labelled fixture at
  `src/test/resources/fixtures/patients.csv` exists and is unused)

## Honest limitations

- **No blocking yet**, so nothing here scales past a few thousand records. A million records is
  ~500 billion pairs; blocking is what makes the problem tractable at all, and it is unimplemented.
- **`token-cosine` has no IDF**, so agreeing on "Street" counts as much as agreeing on "Kowalczyk".
  Fixing it needs corpus-wide document frequencies, which would stop the comparator being a pure
  function of its two arguments — a real design change, not a tweak.
- **Soundex is from 1918 and shows it.** It models English consonant phonology and degrades on names
  of South Asian, Chinese or Slavic origin, which is a genuine fairness problem in a system deciding
  whether two people are the same. It also keeps the initial letter verbatim, so `Karl`/`Carl` and
  `Catherine`/`Katherine` score zero — and Jaro-Winkler independently misses those too, being least
  forgiving at the prefix. For that error class both comparators fail together. Double Metaphone plus a
  nickname table is the fix.
- **No quality numbers yet.** Until the benchmark harness runs against labelled data, any claim about
  accuracy here would be unfounded.

## Building

Requires JDK 21 and Maven.

```bash
mvn -B test
```

Running the app is not useful yet — the Flyway migration is missing, so startup fails.

## Layout

```
domain/model        SourceRecord, RecordId
domain/schema       ResolutionSchema — attributes, comparators, weights, thresholds
domain/match        comparator SPI, registry, scorer, decisions
domain/cluster      UnionFind, ClusterPolicy
domain/link         link ledger, provenance, un-merge
domain/port         repository interfaces (no implementations yet)
config              Spring wiring — the only place the framework appears
```
