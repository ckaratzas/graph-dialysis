# Recursive Decomposition and Query Ordering

**Status: implemented and measured (Parts 1–4, 7), not adopted (Parts 5–6).** Parts 1–4 define the
piece decomposition and position signature, implemented in `Peel.kt` and self-contained here. Part 7's
measurement — whether the signature discriminates within a colour class — found it does not
discriminate any better than the per-query filter already in production use (see
`FINAL_MEASUREMENTS_SPEC.md` Task 3), so the query-ordering and seeding uses in Parts 5–6 were never
wired into the driver. They remain below as documented, unimplemented designs, in case a future
instance regime makes the signature worth revisiting.

**Read this first.** Everything in this document is a **heuristic**. It changes *which query is asked
first* and *which vertex is individualized*, never which answers are possible. Correctness therefore
does not depend on any property of the decomposition — not determinism, not invariance, not
canonicity. Every conclusion is still produced by the SAT layer and its `O(m)` witness verification.
Nothing here has a step whose correctness would depend on the decomposition being canonical; it is not.

---

## Part 1 — Dialysis: the one-level decomposition

### 1.1 Definition

Given a graph `G = (V,E)` and a root `r`:

```
DIALYSIS(G, r):
    compute BFS layers L_0 = {r}, L_1, L_2, ... from r
    T <- {r}
    parent <- empty map
    for i = 1, 2, 3, ... in increasing order:
        for each v in L_i:                          # order within the layer is irrelevant
            if |N(v) ∩ T ∩ L_{i-1}| == 1:
                T <- T ∪ {v}
                parent[v] <- the unique neighbour of v in T ∩ L_{i-1}
    O <- { v ∈ V \ T : N(v) ⊆ T }                   # orphans
    R <- connected components of the subgraph induced on V \ (T ∪ O)   # remainders
    return (T, parent, depth, O, R)
```

`depth[v]` is the BFS distance from `r`.

**Why admission tests only `T ∩ L_{i-1}`.** That set is fully determined before layer `i` is
processed, so the result does not depend on the order in which the vertices of `L_i` are visited. If
the test instead referenced `T ∩ L_i`, which is being built during the layer, the output would depend
on traversal order.

**T is a tree when `G` is bipartite.** Each admitted vertex gets exactly one parent one layer up, and
in a bipartite graph there are no edges inside a layer, so no cycle can close. On a **non-bipartite**
graph, intra-layer edges exist and `T` may contain a cycle — the AHU labels of Part 2 are then
meaningless. Either subdivide first, or skip Parts 2–3 for that graph and use only the piece key from
Part 4, which does not depend on `T` being acyclic.

`(T, O, R_1, …, R_k)` partitions `V`.

### 1.2 Edge classes

```
slice edge   one endpoint in T, the other in O      -- an orphan's attachment
connector    one endpoint in T, the other in some R_j
```

For `o ∈ O`, `att(o) = N(o) ⊆ T` is its **attachment set**; every neighbour of an orphan lies in `T`
by definition, so the slice of `o` is a star.

---

## Part 2 — Colored AHU labels for the tree

Needed for the position signature in Part 4. Standard AHU encoding, bottom-up.

```
AHU(T, parent, root, base):
    children[v] <- [] for all v in T
    for v in T, v != root:  children[parent[v]].append(v)

    process vertices in order of DECREASING depth:
        for each v:
            kids <- sorted( [ label[c] for c in children[v] ] )      # sort the STRINGS, not the ids
            label[v] <- canonical_string( base(v), kids )
    return label                                                     # label[root] identifies the tree
```

`base(v)` is whatever per-vertex colour is being used; the uniform colour if none. `canonical_string`
must be a deterministic serialization of the pair, and it must never include a vertex identifier.
Interning the strings to integers is fine for memory, but see the warning in Part 6.

`label[v]` is the canonical form of the subtree rooted at `v`; two vertices carry the same label iff
their subtrees are isomorphic as coloured rooted trees.

---

## Part 3 — Recursive decomposition into pieces

```
PEEL(G, X, colouring, rootRule, out):          # X is a vertex subset; out collects the pieces
    sub <- G restricted to X
    if sub is a tree, or the stable refinement of sub is discrete:
        out.add( Piece(vertices = X, kind = BASE) )
        return
    r <- rootRule(sub, colouring)
    (T, parent, depth, O, R) <- DIALYSIS(sub, r)
    out.add( Piece(vertices = T ∪ O, kind = QUOTIENT, tree = T, parent = parent,
                   depth = depth, orphans = O, root = r) )
    if R is empty: return
    for each component C in R:
        PEEL(G, C, colouring, rootRule, out)
```

Call it as `PEEL(G, V, colouring, rootRule, pieces)`.

**`rootRule`** may be anything deterministic — the least-indexed vertex of the smallest colour class
is fine. It does **not** need to be invariant, because nothing downstream depends on the pieces being
canonical.

**Termination.** Every recursive call is on a remainder, which excludes at least the root `r`, so each
call is on a strictly smaller set.

**Assert, in the port:** the pieces are pairwise disjoint and their union is `V`. That property is
what makes the signature in Part 4 well defined (every vertex lies in exactly one piece), and a
violation means a bug in the remainder computation.

---

## Part 4 — The position signature

```
POSITION_SIGNATURES(G, pieces):
    sig <- empty map
    for each piece P in pieces:
        key <- piece_key(P)                         # see below
        if P.kind == QUOTIENT:
            ahu <- AHU(P.tree, P.parent, P.root, base = uniform)
            for v in P.tree:
                sig[v] <- ( key, ahu[v], P.depth[v], "TREE" )
            for o in P.orphans:
                attach <- sorted( [ ahu[t] for t in att(o) ] )      # multiset of attachment labels
                sig[o] <- ( key, canonical_string(attach), -1, "ORPHAN" )
        else:
            for v in P.vertices:
                sig[v] <- ( key, "", -1, "BASE" )
    return sig
```

### 4.1 `piece_key` must be content-addressed

```
piece_key(P) = canonical_string(
    |P.vertices|,
    uncoloured_certificate( G restricted to P.vertices ),      # e.g. a canonical form of the piece
    sorted multiset of the colours of P's boundary vertices    # vertices with a neighbour outside P
)
```

**Never use the piece's index in the `pieces` list, its recursion depth, or its discovery order.**
Those depend on traversal and would make the ordering vary between runs on the same graph. Results
would still be *correct* — ordering cannot change an answer — but they would not be reproducible, and
reproducibility is claimed elsewhere.

If computing an uncoloured certificate per piece is too expensive, a cheaper content-addressed
substitute is acceptable: `(|P|, |E(P)|, sorted degree sequence of P, sorted boundary colours)`.
Record which was used.

---

## Part 5 — Use 1: query ordering (design only — not adopted, see Part 7's result)

The driver asks pairwise queries `(u,v)` **within** a colour class. Order them by descending
structural similarity.

```
tier(u, v):
    if sig[u] == sig[v]:                                    return 1
    if key(u) == key(v) and ahu(u) == ahu(v):               return 2     # same piece type and position
    if key(u) == key(v):                                    return 3     # same piece type
    return 4

order pairs by:  tier ascending,
                 then dist(u,v) ascending,
                 then |N(u) ∩ N(v)| descending
```

**Rationale.** Tier-1 pairs are locally indistinguishable inside their piece, so they are the most
likely to be orbit-mates, so the most likely to return SAT — and a satisfiable query yields an
automorphism which, applied to every vertex, may close a whole orbit at once. Tier-4 pairs are the
most likely to return UNSAT, which closes one pair (or, with separation transitivity, one pair of
components).

**Acceptance test.** On any instance, the partition returned with ordering enabled must be
**identical** to the partition returned without it. Ordering cannot change the answer; if it does,
there is a bug in the driver, not in the ordering.

---

## Part 6 — Use 2: individualization seeding (design only — not adopted, see Part 7's result)

For the low-colour-class families, the per-query filter individualizes a vertex and refines. When
there is a choice of which vertex to individualize, prefer one whose position signature is **rarest**
within its colour class:

```
seed_vertex(C, sig) = argmin over v in C of  |{ w in C : sig[w] == sig[v] }|
                      ties broken deterministically
```

A vertex in a small signature group is structurally distinctive, so individualizing it is more likely
to shatter the colouring than individualizing an arbitrary member of a large group.

---

## Part 7 — Measure the signature before measuring its effect

The signature's discriminating power decides whether Parts 5 and 6 are worth building, so it was
measured first, before either:

```
for the target instance:
    compute the colouring c and the signatures sig
    for each colour class C with |C| > 1:
        report |C|, and the number of DISTINCT signatures among the members of C
    report the distribution over classes
```

### How to read it

```
1 distinct signature per class          the decomposition cannot discriminate inside a class.
                                        Ordering is a no-op and seeding picks arbitrarily.

2-3 distinct signatures per class        marginal; ordering can only reorder a coarse partition of
                                         the pairs. Expect little.

many distinct signatures per class       real discriminating power; worth building Parts 5 and 6.
```

**Prior result, for calibration.** On `cfi-rigid-d3` this signature was nearly constant within colour
classes: 523 of 529 queried pairs fell into tier 1, and query counts with and without ordering were
identical (529 = 529, 178 = 178). Expected there, because a `Pi` colour class already forces
near-identical dialysis structure.

**Result on the low-colour-class families.** `ag`, `had`, `latin`, `lattice`, `paley`, `triang`, `pg`,
`grid-w` are a different regime — classes of size `Θ(n)`, where `Pi` gives almost no separation — so it
was an open question whether the decomposition discriminates inside them. It measured no better than
the per-query filter already in production use (`FINAL_MEASUREMENTS_SPEC.md` Task 2): the signature
does not shrink or reorder the search meaningfully beyond what that filter already achieves. Per the
decision rule above, that is the negative case — Parts 5 and 6 were not built into the driver.

---

## Part 8 — Reporting

The `SIGNATURE` block is what Part 7 actually reported. `ORDERING` and `SEEDING` were never populated,
since Part 7's result didn't justify building Parts 5–6 — kept here as the schema to reuse if a future
instance regime does justify them.

```
SIGNATURE
  pieces, piece_sizes (min/median/max)
  signatures_total
  per class: |C| and distinct signatures     (distribution: min / median / max)
  signature_ms

ORDERING (only if Part 7 justifies it)
  tier1_issued, tier1_sat, tier1_unsat
  tier4_issued, tier4_sat, tier4_unsat       <- tier1 SAT rate vs tier4 SAT rate is the direct test
  queries_issued  with ordering  vs  without
  first_sat_query_index per class            <- how many queries before the first witness
  partition_identical                        <- MUST be true

SEEDING (only if Part 7 justifies it)
  classes after individualization: rarest-signature seed vs arbitrary seed
  variables and clauses in the per-query formula, both ways
```

The decisive comparison for ordering is **tier-1 SAT rate against tier-4 SAT rate**. If they are the
same, the signature carries no predictive information regardless of what the total times look like,
and the ordering should be dropped.

---

## Guard rails

- **Ordering must never change the answer.** Assert identical partitions; a difference is a bug.
- **Content-addressed piece keys only.** No indices, no depths-in-the-piece-list, no discovery order.
- **Beware global intern pools.** If AHU labels or canonical strings are interned into integers from a
  process-lifetime pool, the integers depend on what else the process has computed. Sorting or
  comparing by *magnitude* then varies between runs. Sort by the **canonical string**, or use ids only
  for equality tests, never for ordering.
- **Non-bipartite input:** either subdivide before Part 1, or skip Parts 2–3 and use only the piece
  key. Do not run AHU on a `T` that may contain a cycle.
- **Assert the pieces partition `V`** at the end of Part 3.
- **Part 7 before Parts 5 and 6.** One number decides whether the rest is worth building.
