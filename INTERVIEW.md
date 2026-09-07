# Coalesce — interview guide

Everything in this file is checked against the code and against a real run of
`mvn -q compile exec:java` on the 95-record fixture. Numbers quoted here are the numbers the
engine actually printed.

---

## 1. The 60-second answer

> Coalesce is an entity resolution engine — it decides when two records that look different are
> actually the same person, and groups them.
>
> It does that in three stages. First it compares two records field by field, using a different
> similarity algorithm per field, and combines those into one weighted score. Second it turns that
> score into one of three decisions: match, no-match, or send to a human. Third it groups the
> matches into clusters using union-find.
>
> The interesting problems weren't the string matching. They were: how do you score a pair when
> half the fields are empty, how do you stop weak matches from chaining unrelated people together,
> and how do you undo a merge after the fact. I kept the core logic free of any framework, which
> let me port the same rules to Apex and run them natively inside Salesforce.

If they want one sentence: **it answers "are these two records the same person, and why do you
think so".**

---

## 2. The problem, in plain terms

Three hospital systems each hold a record for the same patient:

| Source | Name | DOB | Postcode | Phone |
|---|---|---|---|---|
| EMR | Jonathan Smith | 1985-03-02 | SW1A 1AA | 020 7946 0958 |
| Lab | Jon Smith | 1985-03-02 | SW1A 1AA | *(missing)* |
| Claims | J. Smyth | 02/03/1985 | SW1A1AA | 02079460958 |

No shared id. Every field differs textually. A human sees one person instantly. `=` sees three
people. Everything in this project exists to close that gap.

**Why it's genuinely hard, and worth saying out loud:**

1. **Similarity is not transitive.** A resembles B, B resembles C, but A and C may be strangers.
2. **Missing data is not disagreement.** "No middle name on file" is not evidence of two people.
3. **It's quadratic.** A million records is 500 billion pairs.
4. **Mistakes are expensive in both directions.** Over-merge and you show one patient another's
   medical history. Under-merge and you miss a drug interaction.

---

## 3. The pipeline, end to end

```
records
   |
   v
[ blocking ]           only compare pairs that share a cheap key
   |                   (implemented in the Apex script, NOT in the Java engine — see §9)
   v
[ PairScorer ]         compare field by field -> one score in [0,1] + a decision
   |
   v
[ ClusterPolicy ]      should this match be allowed to merge these two clusters?
   |
   v
[ UnionFind ]          apply the merge; maintain connected components
   |
   v
[ LinkLedger ]         store WHY, so the merge can be undone later
   |
   v
clusters + a review queue for the borderline pairs
```

Each stage is a separate class with no knowledge of the next. That is what let me swap the whole
back half out and re-implement it in Apex.

---

## 4. Class by class — what it does, and the one thing to say about it

### The data

**`RecordId`** — a record's identity as `(source, local)`, e.g. `emr:E1001`. Two systems can both
have a record "1"; qualifying by source keeps them distinct.

**`SourceRecord`** — an id plus `Map<String, String>` of attributes.
> **Say this:** every attribute is a String on purpose. The engine has to handle patients, suppliers
> and bank customers without recompiling, so it can't have typed fields. The typing lives in the
> schema — a date comparator interprets `"1985-03-02"` as a date, an exact comparator sees opaque
> text. The constructor drops null and blank values, so "absent" and "present but empty" collapse
> into one thing, which matters because absence is treated specially everywhere downstream.

**`CandidatePair`** — an unordered pair, sorted at construction so `(A,B)` and `(B,A)` are one value.
> **Say this:** it's canonical for a reason. Several blocking strategies find the same pair in
> different orders. Without canonical form you'd score it twice, and you'd write two links for one
> pair — so a later un-merge would remove one and leave the cluster silently intact. Also it sorts
> by `(source, local)` rather than hash code, so pair identity survives a JVM restart.

### The comparing

**`AttributeComparator`** — the interface every similarity algorithm implements. Two methods:
`id()` and `compare(left, right) -> OptionalDouble`.

This interface is the single best thing to talk about, because its contract has four clauses and
each one is load-bearing:

| Clause | Why |
|---|---|
| Returns `[0,1]`, not a boolean | "Jon" vs "John" is neither match nor mismatch. Collapsing to a boolean throws away the only information the scorer can use. |
| Returns **empty**, not 0.0, when a value is absent | Scoring missing as 0 penalises sparse records — which are exactly the records most likely to be duplicates. |
| Must be **total** — never throw | Input is untrusted text. A date comparator handed `"unknown"` must return empty, not kill a batch of a million comparisons over one bad cell. |
| Must be **symmetric** | Clustering assumes an undirected graph. An asymmetric comparator makes clusters depend on record insertion order — near-impossible to debug. |

**`ComparatorRegistry`** — maps comparator ids (`"jaro-winkler"`) to implementations. Rejects
duplicate ids at construction.
> **Say this:** this indirection is what makes the engine schema-agnostic — a schema is *data*, not
> code. Duplicate ids fail loudly because two comparators competing for `"exact"` would make schema
> behaviour depend on classpath ordering.

**The seven comparators** — see §5 for how each algorithm works.

| id | Class | Used for |
|---|---|---|
| `exact` | `ExactComparator` | ids, postcodes, emails — no partial credit |
| `jaro-winkler` | `JaroWinklerComparator` | personal names |
| `levenshtein` | `LevenshteinComparator` | general short text |
| `soundex` | `SoundexComparator` | names transcribed by ear |
| `token-cosine` | `TokenCosineComparator` | addresses, company names |
| `numeric` | `NumericComparator` | quantities |
| `date` | `DateComparator` | dates of birth |

**`AttributeComparatorSpec`** — one row of a schema: `(attribute, comparator, weight, blocking)`.
Rejects zero or negative weights, because "this field can't influence the decision" is better said
by leaving it out than by encoding it as a number someone thinks is configured.

**`ResolutionSchema`** — the whole matching configuration: which fields, which comparator, what
weight, where the thresholds sit, plus a version number.

Validation at construction is the thing to highlight — a schema is the one place a plausible-looking
mistake produces *wrong answers* rather than a crash:

- duplicate attribute → would double-weight one field
- `reviewThreshold > matchThreshold` → would leave no match band at all
- no blocking attribute → silently degrades to all-pairs, fine at 500 records and fatal at a million
- `minContributingAttributes > attributes.size()` → makes MATCH unreachable, so the whole dataset
  lands in the review queue and it looks like a quality problem instead of a config error

> **Say this:** the version is *part of* the schema, not metadata. Every link stores it. Without
> that, changing a threshold makes every existing link unexplainable — a steward asks "why is this
> merged", you re-score it against today's rules, it comes out below today's threshold, and you have
> no answer.

It uses a **builder** because the constructor takes seven arguments, two of which are unrelated
thresholds in `[0,1]`. Positional construction is exactly the shape that lets you transpose two
numbers and get a working-but-wrong schema.

**`PairScorer`** — the heart. `score(schema, left, right) -> ScoredPair`.

```
for each attribute in the schema:
    if either side is missing it            -> record as absent, skip
    similarity = comparator.compare(a, b)
    if the comparator returns empty          -> record as absent, skip
    if similarity is outside [0,1]           -> throw, naming the comparator
    weightedSum   += weight * similarity
    presentWeight += weight

score         = weightedSum / presentWeight      <- renormalisation
evidenceShare = presentWeight / schema.totalWeight()
decision      = decide(score, count of contributing attributes, evidenceShare)
```

**Renormalisation is the whole point** and it's the best thing on your resume to be asked about:

> Dividing by the weight of only the *present* attributes, not all of them. The naive alternative —
> score missing as 0 and divide by full weight — doesn't add noise evenly. It punishes sparse
> records specifically, and sparse records are disproportionately the duplicates, because a record
> with half its fields blank is usually the one that got typed in twice. A pair agreeing perfectly
> on name, DOB and national id would score 0.6 purely for having no address on file, and fall below
> any sensible threshold.

**But renormalisation creates its own bug, and this is where the story gets good.** If the
denominator shrinks with the evidence, then confidence is no longer tied to how much you knew. Two
records sharing only `country = "US"` score a perfect 1.0. Meaningless. And in real data this
isn't a corner case — sparse records cluster together and merge into one giant blob of "records we
know nothing about".

So there are two guards, both schema config:

- `minContributingAttributes` — at least N fields must be comparable
- `minEvidenceWeightShare` — those fields must carry at least X% of total weight

Both are needed. Count alone treats three weak fields as stronger than one decisive one, which
inverts what the weights were written to say.

A pair that clears the match threshold but fails a guard is demoted to **REVIEW, not NO_MATCH**.
It looks like a match on the evidence available; what's missing is evidence, and a human is the
right resolution. Demoting to NO_MATCH would be treating absence as disagreement — the same
original error, one level up.

**`ScoredPair`** — the result: score, decision, per-attribute similarities, and the list of
attributes that couldn't be compared.
> **Say this:** the breakdown is part of the value, not a debug log. A steward never asks "what was
> the score", they ask "why". `absentAttributes` is deliberately as prominent as the scores, because
> "these two disagree on address" and "neither record has an address" are opposite conclusions that
> one number can't distinguish.

**`MatchDecision`** — `MATCH` / `REVIEW` / `NO_MATCH`. Three-way, not boolean. The review band is
where the system admits it doesn't know.

### The clustering

**`UnionFind<T>`** — disjoint-set forest with **path halving** and **union by rank**.

What it's for: matching produces edges ("A is B"). You need connected components of that graph.

Why not the obvious alternatives — have this ready, it's a classic follow-up:
- re-running BFS per query is O(V+E) every time
- a `Map<Entity, Set<Record>>` merged on every link is O(n) per merge, so a million links is
  quadratic

Union-find gives O(α(n)) amortised, where α is the inverse Ackermann function and is under 5 for
any input that fits in the universe. Effectively constant.

The two optimisations and why you need both:
- **Union by rank** attaches the shallower tree under the deeper one, so an adversarial merge order
  can't degenerate the forest into a linked list.
- **Path halving** points each node at its grandparent while traversing, flattening the tree as a
  side effect of reading it.

Either alone gives O(log n); together they give the inverse-Ackermann bound. Path halving rather
than full two-pass compression because it flattens nearly as well in one pass with no recursion,
and `find` is called far more often than `union`.

`union` returns a boolean — true if a merge happened, false if they were already together. Callers
need that distinction so redundant links aren't counted as work.

> **Say this:** this class does *only* transitive closure, and transitive closure is not a correct
> resolution policy. Keeping the graph algorithm free of policy is exactly what makes both testable
> on their own.

**`ClusterState`** — wraps `UnionFind` and adds one thing union-find deliberately doesn't have:
cheap enumeration of a cluster's members.

Why it's a separate class: `ClusterPolicy` needs both membership lists to sample across the
boundary. `UnionFind.clusters()` is O(n) per call, so calling it per candidate link makes the run
quadratic in records — the exact cost blocking exists to avoid. Pushing membership into `UnionFind`
was the rejected alternative: it'd cost memory for every caller including ones that only ask about
connectivity, and put policy concerns inside a graph algorithm.

Membership lists merge **smaller into larger**, so any element is copied at most log n times.
Always appending B to A is O(n) per merge and degrades in precisely the case that matters — one
big cluster absorbing records one at a time, which is what a real run looks like.

Subtle detail worth knowing: union-find picks a winner by *rank*, `ClusterState` picks by *size*,
so they can disagree about which side won. The merged list is re-keyed to whichever root union-find
chose, so the two structures agree on identity.

**`ClusterPolicy`** — decides whether a pairwise match is allowed to become a cluster merge. This
is the most interesting class in the project.

The problem it solves, in one example:

```
"Jon Smith, London"  ~  "John Smith, London"  ~  "John Smyth, London"  ~  "Joan Smyth, London"
```

Every link is individually defensible. The endpoints are different people. Run this over real data
and it isn't a few bad merges, it's a phase transition — one component swallows a large fraction of
the dataset, and because every merge was plausible, **nothing in the logs looks wrong.**

> **Have this ready:** "why not just raise the threshold?" Because raising it until no chain forms
> also throws away the genuinely borderline true matches. That trades a catastrophic precision
> failure for a large recall failure. The chain has to be broken using something a single pair score
> cannot see: what the resulting cluster would look like.

`evaluate()` runs checks cheapest-and-most-decisive first:

1. **Is the pair even a MATCH?** No → refuse (`NOT_A_MATCH` / `REVIEW_REQUIRED`)
2. **Already in one cluster?** → `ALREADY_CONNECTED`, redundant link
3. **Manual separation anywhere across the two clusters?** → refuse, humans outrank scores
4. **Would the merged cluster exceed `maxClusterSize`?** → refuse (circuit breaker)
5. **Cohesion sample** — the only check that costs comparisons

**Cohesion** is the real defence. Before merging clusters A and B, sample record pairs across the
boundary and require their mean similarity to clear a threshold. In an A~B~C~D chain, merging
`{A,B,C}` with `{D}` forces (A,D) and (B,D) to be scored — the comparisons transitive closure never
makes — and their low scores refuse the merge. It's a local approximation of correlation clustering,
which is NP-hard to optimise globally.

**Max cluster size** is a blunt circuit breaker for what cohesion misses: a source system emitting
thousands of `"TEST TEST"` rows forms a genuinely cohesive cluster that is nonetheless wrong.

Two design decisions worth defending:

- **Sampling, not exhaustive.** Exhaustive is |A|×|B|; merging into a 500-member cluster one record
  at a time costs 500 comparisons for that link alone. Sampling k pairs makes each decision O(k).
  What you give up: standard error ≈ s/√k, so a small sample can miss a minority of bad cross pairs.
- **Deterministic stride, not random.** Reproducibility beats statistical purity: when a steward
  asks why two records didn't merge, the answer must be the same today as in the batch that produced
  it. Random sampling would make refusals unreproducible unless you persisted a seed per decision.
  The cost is a known bias — striding can correlate with merge order. Reservoir sampling with a
  persisted seed is the proper fix, not implemented.

**The candidate link is excluded from its own cohesion sample.** Including it would let a merge
corroborate itself: for two singletons the link *is* the only cross pair, so cohesion would equal
the pair score, which already cleared a higher threshold — the check would always pass and measure
nothing. See §9 for the consequence of this.

**`LinkVerdict`** — a result object, not a boolean. Carries the outcome enum, a human-readable
reason, the cohesion score, and the resulting size. Every refusal explains itself.

### The provenance

**`EntityLink`** — one pairwise assertion, stored as a fact: pair, score, decision, schema name +
version, timestamp, and origin (`AUTOMATIC` or `MANUAL_OVERRIDE`).

> **This is the best architecture answer in the project.** The tempting design is to store the
> cluster — a `cluster_id` column on each record. It's smaller and it makes lookups a single indexed
> read, and it is **unrecoverable**. A cluster from transitive closure is a *conclusion*; the links
> are the *premises*. Once you've stored only the conclusion, nothing can undo part of it — removing
> one record can't know whether the rest still belong together, because the evidence is gone. Every
> merge becomes permanent, which in a system deciding whether two people are the same person is not
> an acceptable property. So Coalesce stores links and derives clusters. The cost is a graph
> traversal instead of an indexed read, and the cluster table becomes a cache rather than the truth.

**`LinkLedger`** — append-only history, and the authority on what the clusters currently are.

Key methods:

| Method | Does |
|---|---|
| `record(link)` | Appends to history, applies it if allowed. Returns whether anything changed. |
| `unmerge(a, b, at)` | Retracts one joining link, rebuilds only the affected component. |
| `separate(a, b, ...)` | Durable human assertion: these are different people. |
| `merge(a, b, ...)` | Human assertion: these are the same. Clears an earlier separation. |
| `component(id)` | BFS over surviving links to get the cluster. |
| `pathBetween(a, b)` | Shortest chain of links joining two records. |
| `reviewQueue()` | Pairs awaiting a human. |

**Why un-merge takes two record ids, not a cluster id and a record** — a strong answer if asked
about API design:

> Transitive closure isn't invertible. A cluster doesn't remember which links produced it, so
> "remove record X from this cluster" has no well-defined answer. Retracting a *link* does: delete
> that edge, recompute components. And only one component can be affected, since a link's endpoints
> are by definition in the same component. So the rebuild is O(cluster size), not O(dataset) — a
> 12-member cluster splitting inside 50 million records touches 12 records. It's also the honest
> operation: the steward is saying "this specific piece of evidence is wrong", which the system can
> act on, rather than "this record doesn't belong", which it can't.

**Human assertions are enforced in two places, deliberately.** `LinkLedger` blocks the direct link;
`ClusterPolicy` blocks merges across any separated pair anywhere in the two clusters. One isn't
enough — blocking only the direct link would let the assertion be defeated transitively through a
third record, which from the steward's point of view is the engine ignoring them.

**Un-merge and separate are different operations on purpose.** `unmerge` retracts evidence, so the
next run may legitimately recreate the link. `separate` asserts a fact that survives every run.
Collapsing them would mean either every retraction is permanent or no assertion is.

`pathBetween` uses **BFS, not union-find**, because the question is not "are they connected" but
"through what" — and union-find cannot answer the second at all. Its parent pointers are by rank
and reconstruct nothing about the original edges.

### Evaluation

**`LabelledFixture`** — loads the 95-record CSV with ground-truth entity labels.

**`ResolutionRun`** — orchestrates one full run: score every pair, gate each match through the
policy, apply the allowed ones. Can run with the policy engaged *or bypassed*, so you can show
exactly what the policy buys.

It memoises scores in a shared cache, because the policy needs scores for pairs the main loop
hasn't reached. Without the cache, cohesion sampling would rescore boundary pairs repeatedly and
dominate the run.

**`QualityMetrics`** — precision / recall / F1, measured **two ways**, and knowing why is a strong
answer:

- **Pairwise** treats it as classifying every pair. Standard, and biased: a cluster of n records
  contributes n(n−1)/2 pairs, so one large entity dominates. Getting one 20-record cluster right
  earns 190 true positives; getting nineteen 2-record clusters right earns 19. A system can post
  great pairwise numbers while being wrong about most *entities*.
- **Cluster-level** counts an entity correct only on an exact set match — no missing member, no
  extra. Unforgiving on purpose, because it matches what a user experiences.

> **Say this:** reporting only one is how a resolution system ends up sounding better than it is.
> The *gap* between them is diagnostic — high pairwise with low cluster-level means errors are
> concentrated in a few big entities, which is a different problem from errors scattered everywhere.

---

## 5. The algorithms, in plain English

### Jaro-Winkler — for names

Two steps.

**Jaro** counts characters that appear in both strings *within a sliding window* of
`max(len)/2 − 1` positions, then counts transpositions (matched characters that appear in a
different order). The window is what stops `"abcdef"` and `"fedcba"` scoring as a perfect anagram.

```
jaro = ( m/|a| + m/|b| + (m−t)/m ) / 3      m = matches, t = transpositions/2
```

**Winkler** then boosts pairs that share a prefix: `jaro + prefix × 0.1 × (1 − jaro)`, capped at 4
characters.

Why it's right for names:
- **Transpositions are cheap.** "Micheal"/"Michael" is one swap. Levenshtein charges two edits;
  Jaro charges half a transposition. Given how common that typo is, this isn't academic.
- **Prefix agreement counts double.** People mangle the ends of names far more than the beginnings.

The boost only applies when Jaro is already ≥ 0.7. Without that gate, unrelated names sharing an
initial ("Smith"/"Sanchez") get lifted toward the threshold — wrong direction to be wrong in.

Verified reference values (from Winkler's paper, asserted in the tests):
`MARTHA/MARHTA = 0.961`, `DIXON/DICKSONX = 0.813`, `DWAYNE/DUANE = 0.840`.

**Where it's wrong:** long strings. "17 Oak Street" vs "Oak Street 17" barely matches
character-wise. And it can't know "Bill" is "William" — no character metric can; that needs a
nickname table, which isn't built.

### Soundex — for names heard, not read

Encodes a name to a letter plus three digits by consonant class (B/F/P/V → 1, C/G/J/K/Q/S/X/Z → 2,
and so on). Vowels break up runs; **H and W are transparent** — they neither encode nor break a run,
which is the rule most implementations get wrong.

Catches what no character metric does: "Robert"/"Rupert" scores ~0.80 on Jaro-Winkler but shares one
Soundex code.

Output is deliberately **1.0 or 0.0** — two codes are either equal or not, and inventing a "60%
alike" would dress a coarse signal up as a precise one.

**Be honest about it, this scores points:** Soundex is from 1918. It's anglocentric and degrades on
South Asian, Chinese and Slavic names — a real fairness problem in a system deciding whether two
people are the same person. And it keeps the first letter verbatim, so "Karl"/"Carl" and
"Catherine"/"Katherine" both score 0 — exactly the pairs Jaro-Winkler also handles badly, so the two
fail together rather than covering for each other. Double Metaphone is the right upgrade.

### Token cosine — for addresses

Split into words, count them, treat each side as a vector, take the cosine of the angle between
them. Order-insensitive, so `"17 Oak Street, Apt 3"` and `"Apt 3, Oak Street 17"` come out
near-identical — the correct answer.

**Known weakness, state it before they do:** no IDF. "Street" counts as much as "Kowalczyk", which
is plainly wrong — a token in half the corpus carries almost no evidence. Fixing it needs
corpus-wide document frequencies injected in, which stops the comparator being a pure function of
its two arguments. That's a real design change, so it's scoped rather than hacked. Schemas mitigate
it by pairing address with a high-signal exact field like postcode.

### Union-find

Every record starts as its own group, pointing at itself. `union(a,b)` points one group's root at
the other's. `find(x)` walks up to the root, and rewires as it goes so the next walk is shorter.

### Blocking

Don't compare all pairs. Bucket records by a cheap key first — exact email, full surname, 4-char
surname prefix — and only compare within buckets.

Measured on the trial org's 156 contacts: **12,090 all-pairs → 34 candidate pairs, a 355× reduction,
4,087 ms → 51 ms, and it found the identical clusters** (zero recall loss).

---

## 6. SOLID — with the exact file that proves each

Don't recite the definitions. Point at the code.

**S — Single Responsibility.**
`UnionFind` does graph connectivity and nothing else; `ClusterPolicy` does merge policy and nothing
else. The comment in `UnionFind` says it outright: *"Keeping the graph algorithm free of policy is
what makes both testable."* `ClusterState` exists as a third class specifically because
membership-tracking is a different job from connectivity — and pushing it into `UnionFind` was
considered and rejected, because it'd cost memory for callers who only ask "same cluster?".

**O — Open/Closed.**
Adding a new similarity algorithm = write one class implementing `AttributeComparator`, register it.
`PairScorer`, `ResolutionRun` and every schema-consuming class are untouched. Adding a nickname
comparator tomorrow changes zero existing files.

**L — Liskov Substitution.**
The `AttributeComparator` contract is written out as four explicit clauses (bounded, empty-not-zero,
total, symmetric). Any implementation must honour all four or the scorer breaks. And `PairScorer`
*enforces* the bounded clause at runtime — a comparator returning outside `[0,1]` throws an
exception naming the offender, rather than quietly skewing every score it touches.

**I — Interface Segregation.**
`ClusterPolicy` doesn't depend on `PairScorer`. It depends on `ScoreOracle`, a one-method interface
(`score(left, right)`). So the chain tests drive the policy with a hand-written score table and no
matching stack at all. Same for `Separations` — one method, `separatedFrom(id)`, implemented by
`LinkLedger`. The policy needs one fact from the ledger, so that's all it asks for.

**D — Dependency Inversion.**
The domain defines its own ports — `RecordRepository`, `LinkRepository`, `ClusterRepository` — as
interfaces in `domain/port`. Infrastructure implements them. The domain layer has **zero Spring
imports**, which is not a slogan here: it's the property that made the Apex port possible, because
I only had to re-implement the adapters, not the rules.

---

## 7. Design patterns actually used

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | `AttributeComparator` + 7 implementations | swap the algorithm per field, by config |
| **Registry** | `ComparatorRegistry` | resolve `"jaro-winkler"` (data) to a class (code) |
| **Builder** | `ResolutionSchema.Builder` | 7-arg constructor with two `[0,1]` thresholds is a transposition trap |
| **Value object** | `RecordId`, `CandidatePair`, `ScoredPair`, `EntityLink` — all immutable records | validated once at construction, safe to share |
| **Result object** | `LinkVerdict` | a boolean can't explain a refusal; this carries outcome + reason + cohesion |
| **Ports & adapters (hexagonal)** | `domain/port/*` | domain defines what it needs; infrastructure supplies it |
| **Memoisation** | score cache in `ResolutionRun` | policy re-asks for scores the main loop already computed |
| **Canonical form** | `CandidatePair` sorts its members | `(A,B)` and `(B,A)` must be one identity |
| **Guard clause / fail fast** | every record's compact constructor | a bad schema fails at startup, not in production weeks later |

---

## 8. Every number on your resume, and how to back it

Run `mvn -q compile exec:java` in the repo to reproduce all of these live.

| Claim | Real figure | Where from |
|---|---|---|
| "0.96 F1" | pairwise precision **1.000**, recall **0.919**, **F1 0.958** | 95-record fixture, 45 true entities |
| cluster-level | **0.903 F1** — 42 of 45 entities exactly right, 48 clusters predicted | exact-set matching, no partial credit |
| "7 comparators" | exact, jaro-winkler, levenshtein, soundex, token-cosine, numeric, date | `ResolutionDemo.registry()` |
| "union-find" | path halving + union by rank, O(α(n)) | `UnionFind` |
| "2 matching fields, 35% weight" | `.evidenceGuard(2, 0.35)` | patient schema |
| "355× less work" | 12,090 pairs → 34 candidates, 4,087 ms → 51 ms, zero recall loss | Apex `allDuplicates.apex`, 156 contacts |
| "22 tests, 93% coverage" | Apex test class, deployed to the trial org | `CoalesceResolutionTest` |
| "171 ms" | one contact vs 155 candidates, record-page path | `findMatchesFor` |
| "same rules in Spring Boot and Apex" | 4 Apex classes + 2 LWCs mirroring the Java domain | `salesforce/force-app` |

**Pairs compared on the fixture:** 4,465 = 95 × 94 / 2. Decisions: 65 match, 12 review, 4,388 no
match. Policy verdicts: 47 allowed, 18 already-connected, **0 refusals**.

### The concrete example to have memorised

The evidence guard firing, straight from the run:

```
1.000  emr:E1012  <->  lab:L2011     truth: SAME entity
       agreed on [postcode=1.00, full_name=1.00]
       not comparable: [phone, address, national_id, dob]
```

A **perfect 1.000 score, demoted to REVIEW.** Why: only postcode (1.5) and full_name (3.0) were
comparable, so evidence share = 4.5 / 13.5 = **33.3%**, under the 35% floor. Two people can share a
postcode and a name and be different people, so the engine refuses to auto-merge on that alone and
asks a human.

This one example demonstrates renormalisation, the evidence guard, three-way decisions and the
review queue all at once. It also shows the guard has a **cost** — truth says they *are* the same
entity, so the guard traded recall for precision here. Being able to say that is worth more than
the number itself.

---

## 9. What is NOT built — say it before they find it

Volunteering these is a strength. Every one is already written into the code comments.

1. **Blocking is not in the Java engine.** `ResolutionRun` compares all n(n−1)/2 pairs and says so
   in its own Javadoc. Blocking exists only in the Apex script. At a million records the Java loop
   is ~500 billion comparisons. The fix is MinHash + LSH banding, and the architecture is ready for
   it — blocking replaces the pair loop and nothing else, which is the payoff for keeping scoring
   and clustering separate.

2. **The cluster policy never actually fired on this fixture.** Guarded and naive runs produce
   *identical* results: same 48 clusters, same 0.958 F1, 0 refusals. **Know why:** cohesion
   deliberately excludes the candidate link from its own sample, so when both sides are singletons
   there's nothing left to sample and it allows unconditionally. Average cluster size here is ~2, so
   the guard structurally cannot engage. It needs an A–B–C–D chain test where links are applied in
   an order that grows `{A,B,C}` before offering D. **This is the single most likely place to get
   caught out** — the honest framing is "the guard is designed and implemented, but this fixture
   doesn't exercise it, and I know exactly what test would."

3. **Weights and thresholds are hand-set, not learned.** Fellegi-Sunter with EM would be better
   calibrated and could say that agreement on a rare surname is worth more than on a common one.
   It needs labelled data or EM over the candidate set — a different project shape. The weighted
   mean was chosen because it's inspectable and needs no training corpus.

4. **No IDF in token cosine** (see §5).

5. **No persistence or REST API.** The ports are defined; the JPA adapters and Flyway migration are
   not written. The Apex port is what actually runs against a real datastore.

6. **Attribute interactions aren't modelled.** Agreeing on both first and last name is treated as
   exactly the sum of its parts.

7. **Two known defects, found by review, not yet fixed:**
   - `ScoredPair` wraps its maps in `Map.copyOf(new TreeMap<>(...))` intending sorted output, but
     `Map.copyOf` returns unspecified iteration order — so `explain()` prints hash-ordered, which is
     exactly the benchmark-diffing pain the comment says it prevents. Fix: `Collections.unmodifiableMap`.
   - `ClusterState.members()`, `clusterSize()` and `representative()` all call `add()`, so
     `ClusterPolicy.evaluate()` mutates state despite its Javadoc promising it doesn't. Harmless
     today because `ResolutionRun` pre-seeds every record, but it's a contract violation.

8. **One small hand-labelled fixture is not a benchmark.** The numbers show the engine works; they
   are not a claim about accuracy on real data.

---

## 10. The Apex port — why it exists and what changed

The Java engine can't run inside Salesforce. So the domain rules were re-implemented in Apex, which
is the practical proof that keeping the domain framework-free paid off.

| Java | Apex |
|---|---|
| `AttributeComparator` + 7 classes | `CoalesceComparator` (static methods) |
| `PairScorer` + `ResolutionSchema` | `CoalesceScorer` (with inner `Schema`, `ScoredPair`, `Decision`) |
| `ResolutionRun` + `UnionFind` | `CoalesceContactResolver` |
| `ResolutionDemo` | two Lightning Web Components |

**Same schema shape, both totalling 13.5 weight**, deliberately, so the two can be compared:

| Field | Comparator | Weight |
|---|---|---|
| email | exact | 4.0 |
| full_name | jaro-winkler | 3.0 |
| birthdate | date | 2.5 |
| phone | phone | 1.5 |
| postcode | exact | 1.5 |
| street | token-cosine | 1.0 |

Thresholds 0.70 / 0.87, evidence guard (2, 0.35) — identical to the Java patient schema.

**Two components, two very different cost profiles — this is a good engineering-judgement story:**

- **Record page** (`coalesceDuplicates`): one contact against many. **Linear.** 155 candidates in
  **171 ms**, so it autoloads — no button, because 171 ms is imperceptible.
- **Home page** (`coalesceDuplicateReport`): all pairs. **Quadratic.** 4,950 pairs in ~4,087 ms, so
  it's **button-triggered** — charging every Home page load four seconds would be unacceptable.

**Platform-specific things worth mentioning:**

- `with sharing` + `WITH USER_MODE` on every query. A resolution engine is an unusually effective
  data-exfiltration surface if it ignores sharing, because its entire output is "here are records
  related to each other".
- A **CPU budget check mid-loop** (7,000 ms of the 10,000 ms limit) that stops and returns partial
  results flagged as truncated, rather than dying with an uncaught `LimitException`.
- **Person Account support**: navigating to a Contact in a person-account org redirects to the
  Account, so a Contact-only component is unreachable. `resolveToContactId` translates an Account id
  to the underlying `PersonContactId` — read via **dynamic SOQL with a describe guard**, because
  the field only exists when the feature is enabled and a static reference would fail to compile in
  orgs without it.
- Cluster ids are the **smallest member id**, not the union-find root, because the root depends on
  the order links were applied. Deriving from membership makes it a pure function of the set.

---

## 11. Likely questions, with answers

**"Why not use a library / Levenshtein for everything?"**
Different error classes need different algorithms. Jaro-Winkler handles typos in names,
Soundex handles names transcribed by ear, token cosine handles reordered addresses. One metric
across all fields would fail on whichever class it wasn't designed for. And hand-writing them meant
I could test against Winkler's published reference values.

**"How do you handle a field being missing?"**
The comparator returns *empty*, not zero, and the scorer divides by the weight of only the present
fields. Missing data is absence of evidence, not evidence of difference. Then two guards stop
renormalisation from producing confident nonsense on sparse pairs.

**"What if A matches B and B matches C but A and C are different people?"**
That's the transitive closure trap and it's the failure mode I built `ClusterPolicy` for. Before
merging, it samples pairs across the cluster boundary and requires their mean similarity to clear a
threshold — which forces exactly the comparisons transitive closure skips. Then be honest: on my
fixture the clusters are too small for it to fire, and I know the test that would exercise it.

**"How would you scale to 10 million records?"**
Blocking is the answer, and it's the main gap. MinHash + LSH banding on name and address shingles,
so you only compare within bands. I measured a 355× reduction using simple email/surname bucketing
in the Apex version with no recall loss. After that, the scoring is embarrassingly parallel — it's
pure functions over pairs — and union-find is the only shared state, which you'd partition by
blocking key.

**"How do you know it works?"**
Precision and recall against a hand-labelled fixture, measured both pairwise and at cluster level,
because pairwise alone flatters you when one big cluster dominates. Currently 1.000 precision,
0.919 recall, 0.958 F1 pairwise; 0.903 at cluster level. And the caveat: one small fixture indicates
the engine works, it doesn't establish accuracy on real data.

**"What would you do differently?"**
Blocking in the Java engine first. Then Fellegi-Sunter with EM-learned weights instead of hand-set
ones, so rare-value agreement counts more than common-value agreement. Then Double Metaphone to
replace Soundex, which is anglocentric and fails on exactly the names it shouldn't.

**"Where's the hardest bug you found?"**
That the cohesion guard can't fire when both clusters are singletons — because the check
deliberately excludes the candidate link from its own sample, and for two singletons that link is
the *only* cross pair. So it samples nothing and allows unconditionally. That's correct behaviour,
not a bug, but it means my fixture never exercises the guard, which I only discovered by comparing a
guarded run against a naive one and getting byte-identical results.
