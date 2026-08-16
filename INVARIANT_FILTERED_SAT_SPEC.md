# The orbit-mate SAT encoding

## What it decides, and why the encoding is complete

For vertices `u, v` in the same colour class, the encoder answers "does some `α ∈ Aut(G)` map `u`
to `v`?" as a SAT instance whose variables range over **all** bijections consistent with an
isomorphism-invariant colouring, not over some restricted structure (a fixed tree, a chosen root,
...). The colouring only *deletes candidate pairs*; it never restricts which automorphisms are
representable. Consequently:

- `SAT` + an O(m)-verified witness ⇒ `u, v` **are** orbit-mates. Proof.
- `UNSAT` ⇒ **no** automorphism maps `u` to `v`. Proof, unconditional.

**Forbidden, because each would break completeness:**
- symmetry-breaking predicates of any kind;
- fixing a root, a tree, or any piece setwise;
- any filter that is not an isomorphism invariant;
- "assume identity outside the query" shortcuts.

---

## Part 1 — the encoder

### 1.1 Variables

```
for i in V, for j in V:
    create x[i][j]  iff  admissible(i, j)
```

`admissible(i,j)` is the conjunction of **isomorphism invariants only**:

```
colour(i) == colour(j)            # the colouring being used for this configuration (see
                                   # BENCHMARK_SPEC.md Part 2's PI_DIST/WL_DIST/PI configs)
deg(i) == deg(j)                  # implied by the above, asserted anyway: cheap, catches bugs
bipartition side consistent       # see 1.4
```

Nothing else. Every additional filter must be justified as an invariant, in a comment, at the point
it is applied.

### 1.2 Clauses

```
# bijection
for i:   exactly-one  { x[i][j] : j admissible for i }
for j:   at-most-one  { x[i][j] : i admissible for j }

# edge preservation
for each edge (i,k) in E:
    for each admissible j for i, each admissible l for k:
        if (j,l) not in E:   (¬x[i][j] ∨ ¬x[k][l])

# the query
x[u][v]                          # unit assumption, not a permanent clause (see 1.5)
```

### 1.3 Edge clauses: conflict form vs. support form

The naive edge-preservation encoding above (**conflict form**) costs `Σ_edges |class(i)|·|class(k)|`
clauses — quadratic in colour-class size, and an OOM risk whenever a class is large and near-uniform
(a class of 600 gives `|E| · 360,000` clauses). An equivalent **support form** avoids this: instead of
forbidding every bad pair, require a good one — if `x[i][j]` holds, some neighbour of `i` must map to
some neighbour of `j`:

```
for each i in V, each admissible j for i, each k in N(i):
    emit  ( ¬x[i][j]  ∨  ⋁_{ l ∈ N(j), l admissible for k }  x[k][l] )
```

One clause per `(i, j, k)` with `k ∈ N(i)`, length `≤ deg(j) + 1` — cost `Σ_i |class(i)|·deg(i)`,
roughly `class_size / 2` times fewer clauses than the conflict form on a large uniform class. Both
forms are **logically equivalent** here: under the bijection constraints, edge-preservation plus
bijectivity forces non-edge preservation by counting, so support form excludes no automorphism and
UNSAT remains an unconditional proof. A clause with an empty support set is a unit contradiction
(`¬x[i][j]`), not an empty disjunction. The encoder picks conflict or support form per edge by
colour-class product size, so small classes (the common case) keep the simpler conflict form and
only large near-uniform classes pay the support form's extra indirection.

### 1.4 Bipartition

If `G` is connected and bipartite with parts `A`, `B`, an automorphism either preserves both parts
or swaps them.

```
|A| != |B|   ->  parts preserved; admissible only within the same part
|A| == |B|   ->  run the query TWICE: once parts-preserved, once parts-swapped; SAT if either
```

Do not assume preservation when the parts are equal in size — that would make UNSAT unsound.

### 1.5 Incremental solving

The base formula depends only on `G` and the colouring, not on the query. Build it **once per
graph** and vary only the query:

```
IPASIR:  solver.assume(x[u][v]); solver.solve()    # single-literal assumption, no push/pop needed
```

All `O(n²)` queries on one graph therefore share one formula and one solver instance, with learned
clauses carried across. This is where most of the wall-clock saving lives.

### 1.6 Implied distance clauses

An automorphism preserves distance: `dist(α(u), α(w)) = dist(u, w)`. So for admissible images `v`
of `u` and `a'` of some anchor `a`, `x[u][v] ∧ x[a][a']` is impossible whenever their distance
buckets disagree — entailed by edge-preservation and bijectivity alone, so adding these clauses
changes propagation strength, never which automorphisms are representable. Only a small set of
anchors (drawn from the smallest colour classes first, since those are the most constrained) are
compared against each vertex, keeping this `O(anchors × variables)` rather than `O(variables²)`.

---

## Part 2 — driving it to orbits

```
for each colour class C with |C| > 1:
    u = any member
    for v in C \ {u}:
        if find(u) == find(v): continue          # already connected by a verified witness
        if separated(u, v): continue              # already proven a different orbit
        r = solve under assumption x[u][v]
        SAT   -> decode alpha; VERIFY in O(m); union(w, alpha(w)) for every w; store alpha as a generator
        UNSAT -> mark (u, v)'s components separated
    orbits within C = components of the union-find
```

Two sound economies:

- **Transitivity + generator closure.** A verified `α: u→v` acts on all of `V`; union `w` with
  `α(w)` for every `w`, not just the queried pair. One witness can collapse many pending queries at
  once.
- **Separation.** A verified UNSAT between two components closes every pair between them, not just
  the one pair queried — and stays closed even as either component later absorbs more vertices.

Queries only ever run **inside** a colour class: two vertices in different classes have different
invariant colours, so no automorphism maps one to the other — a separation obtained for free.

---

## Part 3 — verification

```
verify(alpha, G):  total, injective, and every edge (a,b) of G maps to an edge of G     # O(m)
```

Run on **every** SAT model, always. A rejected model means the encoding is wrong: stop and report,
do not patch around it.

---

## Guard rails

- **Verify every model in O(m).** Always.
- **Timeouts are `UNKNOWN`, never `UNSAT`.**
- **No symmetry breaking.** Stated twice deliberately.
- **Name-freeness:** relabel, re-run, assert the recovered partition is identical *as a partition*.